package reactor.core.processor.rb;

/**
 * @author jbrisbin
 * @author smaldini
 */
public final class MutableSignal<T> implements ImmutableSignal<T> {

	public enum Type {
		NEXT, ERROR, COMPLETE
	}

	public Type      type     = Type.NEXT;
	public T         value    = null;
	public Throwable error    = null;
	public long      seqId    = -1;

	@Override
	public T getValue() {
		return value;
	}

}
