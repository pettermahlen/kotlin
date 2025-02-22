// FIR_DISABLE_LAZY_RESOLVE_CHECKS
// FILE: Dict.java

public abstract class Dict<K, V> {
    abstract public V get(Object key);
}

// FILE: MHashtable.java

abstract public class MHashtable<X, Y> extends Dict<X, Y> implements java.util.Map<X, Y> {
    public V get(Object key) { return null; }
}

// FILE: main.kt

abstract class C1 : MHashtable<String, Int>()

abstract class C2 : MHashtable<String, Int>() {
    override fun <!RETURN_TYPE_MISMATCH_ON_OVERRIDE!>get<!>(key: String) = 1
}
