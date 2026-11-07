package android.system;

/** Minimal test-only Android ErrnoException stub. */
public class ErrnoException extends Exception {
    private static final long serialVersionUID = 1L;
    public ErrnoException(String functionName, int errno) { super(functionName + " errno=" + errno); }
}
