/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.processor;

import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.internal.util.concurrent.ManyToOneConcurrentArrayQueue;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.Traverser;
import com.hazelcast.jet.Traversers;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.BroadcastKey;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.ResettableSingletonTraverser;
import com.hazelcast.jet.core.Watermark;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.datamodel.Tuple3;
import com.hazelcast.jet.function.DistributedBiFunction;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.impl.util.LoggingUtil;
import com.hazelcast.jet.pipeline.ContextFactory;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.hazelcast.jet.Traversers.traverseIterable;
import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.core.BroadcastKey.broadcastKey;
import static com.hazelcast.jet.datamodel.Tuple2.tuple2;
import static com.hazelcast.jet.datamodel.Tuple3.tuple3;
import static com.hazelcast.jet.impl.processor.ProcessorSupplierWithContext.supplierWithContext;
import static com.hazelcast.jet.impl.util.ExceptionUtil.withTryCatch;
import static com.hazelcast.jet.impl.util.LoggingUtil.logFine;

/**
 * Processor which, for each received item, emits all the items from the
 * traverser returned by the given async item-to-traverser function, using a
 * context object.
 * <p>
 * This processors might reorder items: results are emitted as they are
 * asynchronously delivered. However, this processor doesn't reorder items with
 * respect to the watermarks that followed them. That is, a watermark is
 * guaranteed to be emitted <i>after</i> results for all items that occurred
 * before it are emitted.
 *
 * @param <C> context object type
 * @param <T> received item type
 * @param <K> extracted key type
 * @param <R> emitted item type
 */
public final class AsyncTransformUsingContextUnorderedP<C, T, K, R> extends AbstractProcessor {

    private final ContextFactory<C> contextFactory;
    private final DistributedBiFunction<? super C, ? super T, CompletableFuture<Traverser<R>>> callAsyncFn;
    private final Function<? super T, ? extends K> extractKeyFn;

    private C contextObject;
    private ManyToOneConcurrentArrayQueue<Tuple3<T, Long, Object>> resultQueue;
    // TODO we can use more efficient structure: we only remove from the beginning and add to the end
    private final SortedMap<Long, Long> watermarkCounts = new TreeMap<>();
    private final Map<T, Integer> inFlightItems = new IdentityHashMap<>();
    private Traverser<Object> currentTraverser = Traversers.empty();
    private Traverser<Entry> snapshotTraverser;
    private boolean tryProcessSucceeded;

    private Long lastReceivedWm = Long.MIN_VALUE;
    private long lastEmittedWm = Long.MIN_VALUE;
    private long minRestoredWm = Long.MAX_VALUE;
    private int maxAsyncOps;
    private int asyncOpsCounter;

    /** Temporary collection for restored objects during snapshot restore. */
    private ArrayDeque<T> restoredObjects = new ArrayDeque<>();

    @Probe(name = "numInFlightOps")
    private final AtomicInteger asyncOpsCounterMetric = new AtomicInteger();

    /**
     * Constructs a processor with the given mapping function.
     */
    private AsyncTransformUsingContextUnorderedP(
            @Nonnull ContextFactory<C> contextFactory,
            @Nullable C contextObject,
            @Nonnull DistributedBiFunction<? super C, ? super T, CompletableFuture<Traverser<R>>> callAsyncFn,
            @Nonnull Function<? super T, ? extends K> extractKeyFn
    ) {
        assert contextObject == null ^ contextFactory.isSharedLocally()
                : "if contextObject is shared, it must be non-null, or vice versa";

        this.contextFactory = contextFactory;
        this.callAsyncFn = callAsyncFn;
        this.contextObject = contextObject;
        this.extractKeyFn = extractKeyFn;
    }

    @Override
    public boolean isCooperative() {
        return contextFactory.isCooperative();
    }

    @Override
    protected void init(@Nonnull Context context) {
        if (!contextFactory.isSharedLocally()) {
            assert contextObject == null : "contextObject is not null: " + contextObject;
            contextObject = contextFactory.createFn().apply(context.jetInstance());
        }
        maxAsyncOps = contextFactory.getMaxPendingCallsPerProcessor();
        resultQueue = new ManyToOneConcurrentArrayQueue<>(maxAsyncOps);
    }

    @Override
    protected boolean tryProcess(int ordinal, @Nonnull Object item) {
        if (getOutbox().hasUnfinishedItem() && !emitFromTraverser(currentTraverser)) {
            return false;
        }
        asyncOpsCounterMetric.lazySet(asyncOpsCounter);
        @SuppressWarnings("unchecked")
        T castedItem = (T) item;
        if (!processItem(castedItem)) {
            // if queue is full, try to emit and apply backpressure
            tryFlushQueue();
            return false;
        }
        return true;
    }

    @CheckReturnValue
    private boolean processItem(@Nonnull T item) {
        if (asyncOpsCounter == maxAsyncOps) {
            return false;
        }
        CompletableFuture<Traverser<R>> future = callAsyncFn.apply(contextObject, item);
        if (future == null) {
            return true;
        }
        asyncOpsCounter++;
        watermarkCounts.merge(lastReceivedWm, 1L, Long::sum);
        Long lastWatermarkAtReceiveTime = lastReceivedWm;
        future.whenComplete(withTryCatch(getLogger(),
                (r, e) -> resultQueue.add(tuple3(item, lastWatermarkAtReceiveTime, r != null ? r : e))));
        inFlightItems.merge(item, 1, Integer::sum);
        return true;
    }

    @Override
    public boolean tryProcessWatermark(@Nonnull Watermark watermark) {
        assert lastEmittedWm <= lastReceivedWm : "lastEmittedWm=" + lastEmittedWm + ", lastReceivedWm=" + lastReceivedWm;
        // Ignore a watermark that is going back. This is possible after restoring from a snapshot
        // taken in at-least-once mode.
        if (watermark.timestamp() <= lastReceivedWm) {
            return true;
        }
        if (watermarkCounts.isEmpty()) {
            if (!tryEmit(watermark)) {
                return false;
            }
            lastEmittedWm = watermark.timestamp();
        }
        lastReceivedWm = watermark.timestamp();
        return true;
    }

    @Override
    public boolean tryProcess() {
        if (tryProcessSucceeded) {
            tryFlushQueue();
        } else {
            // if we're running tryProcess for the second time, emit just the current traverser
            emitFromTraverser(currentTraverser);
        }
        return tryProcessSucceeded = !getOutbox().hasUnfinishedItem();
    }

    @Override
    public boolean complete() {
        return tryFlushQueue();
    }

    @Override
    public boolean saveToSnapshot() {
        assert restoredObjects.isEmpty() : "restoredObjects not empty";
        if (!emitFromTraverser(currentTraverser)) {
            return false;
        }
        if (snapshotTraverser == null) {
            LoggingUtil.logFinest(getLogger(), "Saving to snapshot: %s, lastReceivedWm=%d",
                    inFlightItems, lastReceivedWm);
            snapshotTraverser = traverseIterable(inFlightItems.entrySet())
                    .<Entry>map(en -> entry(
                            extractKeyFn.apply(en.getKey()),
                            tuple2(en.getKey(), en.getValue())))
                    .append(entry(broadcastKey(Keys.LAST_EMITTED_WM), lastReceivedWm))
                    .onFirstNull(() -> snapshotTraverser = null);
        }
        return emitFromTraverserToSnapshot(snapshotTraverser);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void restoreFromSnapshot(@Nonnull Object key, @Nonnull Object value) {
        if (key instanceof BroadcastKey) {
            assert ((BroadcastKey) key).key().equals(Keys.LAST_EMITTED_WM) : "Unexpected key: " + key;
            // we restart at the oldest WM any instance was at at the time of snapshot
            minRestoredWm = Math.min(minRestoredWm, (long) value);
            return;
        }
        Tuple2<T, Integer> value1 = (Tuple2<T, Integer>) value;
        // we can't apply backpressure here, we have to store the items and execute them later
        for (int i = 0; i < value1.f1(); i++) {
            restoredObjects.add(value1.f0());
            LoggingUtil.logFinest(getLogger(), "Restored: %s", value1.f0());
        }
    }

    @Override
    public boolean finishSnapshotRestore() {
        for (T t; (t = restoredObjects.peek()) != null && processItem(t); ) {
            restoredObjects.remove();
        }
        if (restoredObjects.isEmpty()) {
            // finish current object, we can't return true with a half-emitted item
            if (!emitFromTraverser(currentTraverser)) {
                return false;
            }
            restoredObjects = new ArrayDeque<>(0); // minimize the internal storage
            lastReceivedWm = minRestoredWm;
            logFine(getLogger(), "restored lastReceivedWm=%s", minRestoredWm);
            return true;
        } else {
            tryFlushQueue();
        }
        return false;
    }

    @Override
    public void close() {
        // close() might be called even if init() was not called.
        // Only destroy the context if is not shared (i.e. it is our own).
        if (contextObject != null && !contextFactory.isSharedLocally()) {
            contextFactory.destroyFn().accept(contextObject);
        }
        contextObject = null;
    }

    /**
     * Drains items from the queue until either:
     * <ul><li>
     *     encountering an incomplete item
     * </li><li>
     *     the outbox gets full
     * </li></ul>
     *
     * @return true if there are no more in-flight items and everything was emitted
     *         to the outbox
     */
    @SuppressWarnings("unchecked")
    private boolean tryFlushQueue() {
        for (;;) {
            if (!emitFromTraverser(currentTraverser)) {
                return false;
            }
            Tuple3<T, Long, Object> tuple = resultQueue.poll();
            if (tuple == null) {
                return watermarkCounts.isEmpty();
            }
            asyncOpsCounter--;
            Integer inFlightItemsCount = inFlightItems.merge(tuple.f0(), -1, (o, n) -> o == 1 ? null : o + n);
            assert inFlightItemsCount == null || inFlightItemsCount > 0 : "inFlightItemsCount=" + inFlightItemsCount;
            Long count = watermarkCounts.merge(tuple.f1(), -1L, Long::sum);
            assert count >= 0 : "count=" + count;
            // the result is either Throwable or Traverser<Object>
            if (tuple.f2() instanceof Throwable) {
                throw new JetException("Async operation completed exceptionally: " + tuple.f2(),
                        (Throwable) tuple.f2());
            }
            currentTraverser = (Traverser<Object>) tuple.f2();
            if (currentTraverser == null) {
                currentTraverser = Traversers.empty();
            }
            if (count > 0) {
                continue;
            }
            long wmToEmit = Long.MIN_VALUE;
            for (Iterator<Entry<Long, Long>> it = watermarkCounts.entrySet().iterator(); it.hasNext(); ) {
                Entry<Long, Long> entry = it.next();
                if (entry.getValue() != 0) {
                    wmToEmit = entry.getKey();
                    break;
                } else {
                    it.remove();
                }
            }
            if (watermarkCounts.isEmpty() && lastReceivedWm > lastEmittedWm) {
                wmToEmit = lastReceivedWm;
            }
            if (wmToEmit > Long.MIN_VALUE && wmToEmit > lastEmittedWm) {
                lastEmittedWm = wmToEmit;
                currentTraverser = currentTraverser.append(new Watermark(wmToEmit));
            }
        }
    }

    /**
     * The {@link ResettableSingletonTraverser} is passed as a first argument to
     * {@code callAsyncFn}, it can be used if needed.
     */
    public static <C, T, K, R> ProcessorSupplier supplier(
            @Nonnull ContextFactory<C> contextFactory,
            @Nonnull DistributedBiFunction<? super C, ? super T, CompletableFuture<Traverser<R>>> callAsyncFn,
            @Nonnull DistributedFunction<? super T, ? extends K> extractKeyFn
    ) {
        return supplierWithContext(contextFactory,
                (ctxF, ctxO) -> new AsyncTransformUsingContextUnorderedP<>(ctxF, ctxO, callAsyncFn, extractKeyFn)
        );
    }

    private enum Keys {
        LAST_EMITTED_WM
    }
}
