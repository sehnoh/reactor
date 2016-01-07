/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
package reactor.rx.stream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import reactor.fn.Predicate;

import org.reactivestreams.*;

import reactor.core.subscriber.SubscriberMultiSubscription;

/**
 * Repeatedly subscribes to the source if the predicate returns true after
 * completion of the previous subscription.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamRetryPredicate<T> extends StreamBarrier<T, T> {

	final Predicate<Throwable> predicate;

	public StreamRetryPredicate(Publisher<? extends T> source, Predicate<Throwable> predicate) {
		super(source);
		this.predicate = Objects.requireNonNull(predicate, "predicate");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {

		StreamRetryPredicateSubscriber<T> parent = new StreamRetryPredicateSubscriber<>(source, s, predicate);

		s.onSubscribe(parent);

		if (!parent.isCancelled()) {
			parent.resubscribe();
		}
	}

	static final class StreamRetryPredicateSubscriber<T>
	  extends SubscriberMultiSubscription<T, T> {

		final Publisher<? extends T> source;

		final Predicate<Throwable> predicate;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamRetryPredicateSubscriber> WIP =
		  AtomicIntegerFieldUpdater.newUpdater(StreamRetryPredicateSubscriber.class, "wip");

		long produced;

		public StreamRetryPredicateSubscriber(Publisher<? extends T> source, 
				Subscriber<? super T> actual, Predicate<Throwable> predicate) {
			super(actual);
			this.source = source;
			this.predicate = predicate;
		}

		@Override
		public void onNext(T t) {
			produced++;

			subscriber.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			boolean b;
			
			try {
				b = predicate.test(t);
			} catch (Throwable e) {
				e.addSuppressed(t);
				subscriber.onError(e);
				return;
			}
			
			if (b) {
				resubscribe();
			} else {
				subscriber.onError(t);
			}
		}
		
		@Override
		public void onComplete() {
			
			subscriber.onComplete();
		}

		void resubscribe() {
			if (WIP.getAndIncrement(this) == 0) {
				do {
					if (isCancelled()) {
						return;
					}

					long c = produced;
					if (c != 0L) {
						produced = 0L;
						produced(c);
					}

					source.subscribe(this);

				} while (WIP.decrementAndGet(this) != 0);
			}
		}
	}
}