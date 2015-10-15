/*
 * Copyright (c) 2011-2015 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.support.wait;

import reactor.core.error.AlertException;
import reactor.fn.Consumer;
import reactor.fn.LongSupplier;

import java.util.concurrent.TimeUnit;

/**
 * <p>Phased wait strategy for waiting ringbuffer consumers on a barrier.</p>
 *
 * <p>This strategy can be used when throughput and low-latency are not as important as CPU resource.
 * Spins, then yields, then waits using the configured fallback WaitStrategy.</p>
 */
public final class PhasedBackoffWaitStrategy implements WaitStrategy
{
    private static final int SPIN_TRIES = 10000;
    private final long spinTimeoutNanos;
    private final long yieldTimeoutNanos;
    private final WaitStrategy fallbackStrategy;

    public PhasedBackoffWaitStrategy(long spinTimeout,
                                     long yieldTimeout,
                                     TimeUnit units,
                                     WaitStrategy fallbackStrategy)
    {
        this.spinTimeoutNanos = units.toNanos(spinTimeout);
        this.yieldTimeoutNanos = spinTimeoutNanos + units.toNanos(yieldTimeout);
        this.fallbackStrategy = fallbackStrategy;
    }

    /**
     * Block with wait/notifyAll semantics
     */
    public static PhasedBackoffWaitStrategy withLock(long spinTimeout,
                                                     long yieldTimeout,
                                                     TimeUnit units)
    {
        return new PhasedBackoffWaitStrategy(spinTimeout, yieldTimeout,
                                             units, new BlockingWaitStrategy());
    }

    /**
     * Block with wait/notifyAll semantics
     */
    public static PhasedBackoffWaitStrategy withLiteLock(long spinTimeout,
                                                         long yieldTimeout,
                                                         TimeUnit units)
    {
        return new PhasedBackoffWaitStrategy(spinTimeout, yieldTimeout,
                                             units, new LiteBlockingWaitStrategy());
    }

    /**
     * Block by sleeping in a loop
     */
    public static PhasedBackoffWaitStrategy withSleep(long spinTimeout,
                                                      long yieldTimeout,
                                                      TimeUnit units)
    {
        return new PhasedBackoffWaitStrategy(spinTimeout, yieldTimeout,
                                             units, new SleepingWaitStrategy(0));
    }

    @Override
    public long waitFor(long sequence, LongSupplier cursor, Consumer<Void> barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        long startTime = 0;
        int counter = SPIN_TRIES;

        do
        {
            if ((availableSequence = cursor.get()) >= sequence)
            {
                return availableSequence;
            }

            if (0 == --counter)
            {
                if (0 == startTime)
                {
                    startTime = System.nanoTime();
                }
                else
                {
                    long timeDelta = System.nanoTime() - startTime;
                    if (timeDelta > yieldTimeoutNanos)
                    {
                        return fallbackStrategy.waitFor(sequence, cursor, barrier);
                    }
                    else if (timeDelta > spinTimeoutNanos)
                    {
                        Thread.yield();
                    }
                }
                counter = SPIN_TRIES;
            }
            barrier.accept(null);
        }
        while (true);
    }

    @Override
    public void signalAllWhenBlocking()
    {
        fallbackStrategy.signalAllWhenBlocking();
    }
}