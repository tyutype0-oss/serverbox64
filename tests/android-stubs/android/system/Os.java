package android.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/** Minimal test-only Android Os.chmod adapter to POSIX filesystem permissions. */
public final class Os {
    private Os() {}

    public static void chmod(String path, int mode) throws ErrnoException {
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(java.nio.file.Paths.get(path), permissions);
        } catch (IOException | UnsupportedOperationException error) {
            ErrnoException wrapped = new ErrnoException("chmod", 1);
            wrapped.initCause(error);
            throw wrapped;
        }
    }
}
