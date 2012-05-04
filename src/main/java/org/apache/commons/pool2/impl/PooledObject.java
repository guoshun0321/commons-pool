/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.pool2.impl;

/**
 * This wrapper is used to track the additional information, such as state, for
 * the pooled objects.
 * <p>
 * This class is intended to be thread-safe.
 *
 * @version $Revision: $
 *
 * @since 2.0
 */
public class PooledObject<T> implements Comparable<PooledObject<T>> {

    private final T object;
    private volatile PooledObjectState state = PooledObjectState.IDLE; // @GuardedBy("this") to ensure transitions are valid
    private final long createTime = System.currentTimeMillis();
    private volatile long lastBorrowTime = createTime;
    private volatile long lastReturnTime = createTime;

    public PooledObject(T object) {
        this.object = object;
    }

    /**
     * Obtain the underlying object that is wrapped by this instance of
     * {@link PooledObject}.
     */
    public T getObject() {
        return object;
    }

    /**
     * Obtain the time (using the same basis as
     * {@link System#currentTimeMillis()}) that this object was created.
     */
    public long getCreateTime() {
        return createTime;
    }

    /**
     * Obtain the time in milliseconds that this object last spent in the the
     * active state (it may still be active in which case subsequent calls will
     * return an increased value).
     */
    public long getActiveTimeMillis() {
        // Take copies to avoid threading issues
        long rTime = lastReturnTime;
        long bTime = lastBorrowTime;

        if (rTime > bTime) {
            return rTime - bTime;
        } else {
            return System.currentTimeMillis() - bTime;
        }
    }

    /**
     * Obtain the time in milliseconds that this object last spend in the the
     * idle state (it may still be idle in which case subsequent calls will
     * return an increased value).
     */
    public long getIdleTimeMillis() {
        return System.currentTimeMillis() - lastReturnTime;
    }

    public long getLastBorrowTime() {
        return lastBorrowTime;
    }

    public long getLastReturnTime() {
        return lastReturnTime;
    }

    @Override
    public int compareTo(PooledObject<T> other) {
        final long lastActiveDiff =
                this.getLastReturnTime() - other.getLastReturnTime();
        if (lastActiveDiff == 0) {
            // make sure the natural ordering is consistent with equals
            // see java.lang.Comparable Javadocs
            return System.identityHashCode(this) - System.identityHashCode(other);
        }
        // handle int overflow
        return (int)Math.min(Math.max(lastActiveDiff, Integer.MIN_VALUE), Integer.MAX_VALUE);
    }

    /**
     * Provides a String form of the wrapper for debug purposes. The format is
     * not fixed and may change at any time.
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("Object: ");
        result.append(object.toString());
        result.append(", State: ");
        result.append(state.toString());
        return result.toString();
        // TODO add other attributes
    }

    public synchronized boolean startEvictionTest() {
        if (state == PooledObjectState.IDLE) {
            state = PooledObjectState.MAINTAIN_EVICTION;
            return true;
        }

        return false;
    }

    public synchronized boolean endEvictionTest(
            LinkedBlockingDeque<PooledObject<T>> idleQueue) {
        if (state == PooledObjectState.MAINTAIN_EVICTION) {
            state = PooledObjectState.IDLE;
            return true;
        } else if (state == PooledObjectState.MAINTAIN_EVICTION_RETURN_TO_HEAD) {
            state = PooledObjectState.IDLE;
            if (!idleQueue.offerFirst(this)) {
                // TODO - Should never happen
            }
        }

        return false;
    }

    public synchronized boolean allocate() {
        if (state == PooledObjectState.IDLE) {
            state = PooledObjectState.ALLOCATED;
            lastBorrowTime = System.currentTimeMillis();
            return true;
        } else if (state == PooledObjectState.MAINTAIN_EVICTION) {
            // TODO Allocate anyway and ignore eviction test
            state = PooledObjectState.MAINTAIN_EVICTION_RETURN_TO_HEAD;
            return false;
        }
        // TODO if validating and testOnBorrow == true then pre-allocate for
        // performance
        return false;
    }

    public synchronized boolean deallocate() {
        if (state == PooledObjectState.ALLOCATED) {
            state = PooledObjectState.IDLE;
            lastReturnTime = System.currentTimeMillis();
            return true;
        }

        return false;
    }

    public synchronized void invalidate() {
        state = PooledObjectState.INVALID;
    }
}
