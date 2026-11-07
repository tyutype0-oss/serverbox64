package dev.servers.app.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Chooses a safe default launch command for common Bedrock and Java server
 * layouts. A user-authored start.sh takes priority and remains user-controlled.
 */
public final class ServerCommandResolver {
    private static final int EM_AARCH64 = 183;
    private static final int EM_X86_64 = 62;

    private ServerCommandResolver() {}

    public static String resolve(File serverDirectory) throws IOException {
        if (serverDirectory == null || !serverDirectory.isDirectory()) {
            throw new IOException("Server directory does not exist");
        }
        File startScript = new File(serverDirectory, "start.sh");
        if (startScript.isFile()) {
            return "bash ./start.sh";
        }

        File bedrock = new File(serverDirectory, "bedrock_server");
        if (bedrock.isFile()) {
            int machine = readElfMachine(bedrock);
            if (machine == EM_X86_64) {
                return "chmod +x ./bedrock_server && LD_LIBRARY_PATH=. box64 ./bedrock_server";
            }
            if (machine == EM_AARCH64) {
                return "chmod +x ./bedrock_server && LD_LIBRARY_PATH=. ./bedrock_server";
            }
            throw new IOException("Unsupported Bedrock ELF machine type: " + machine);
        }

        File[] jars = serverDirectory.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".jar") && new File(dir, name).isFile());
        if (jars != null && jars.length == 1) {
            return "java -jar " + shellQuote(jars[0].getName());
        }
        if (jars != null && jars.length > 1) {
            throw new IOException("Multiple JAR files found; set an explicit server start command");
        }
        throw new IOException("No start.sh, Bedrock executable or JAR found");
    }

    /** Reads e_machine from an ELF header without invoking a shell utility. */
    static int readElfMachine(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 20) throw new IOException("ELF file is truncated: " + file.getName());
            if (input.read() != 0x7f || input.read() != 'E'
                    || input.read() != 'L' || input.read() != 'F') {
                throw new IOException("Not an ELF executable: " + file.getName());
            }
            int elfClass = input.read();
            if (elfClass != 2) throw new IOException("Only 64-bit ELF servers are supported");
            int data = input.read();
            if (data != 1) throw new IOException("Only little-endian ELF servers are supported");
            input.seek(18);
            int low = input.read();
            int high = input.read();
            if (low < 0 || high < 0) throw new IOException("Invalid ELF machine field");
            return low | (high << 8);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
