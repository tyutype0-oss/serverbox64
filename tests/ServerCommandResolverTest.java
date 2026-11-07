package dev.servers.app.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

public final class ServerCommandResolverTest {
    private static void writeElf(File file, int machine) throws Exception {
        byte[] h = new byte[64];
        h[0] = 0x7f; h[1] = 'E'; h[2] = 'L'; h[3] = 'F';
        h[4] = 2; // ELFCLASS64
        h[5] = 1; // little endian
        h[18] = (byte) (machine & 0xff);
        h[19] = (byte) ((machine >>> 8) & 0xff);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(h); }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("server-command-test").toFile();
        try {
            File bedrockX64 = new File(root, "bedrock-x64");
            writeElf(bedrockX64, 62);
            check(ServerCommandResolver.readElfMachine(bedrockX64) == 62,
                    "x86-64 ELF machine detection failed");

            File bedrockArm = new File(root, "bedrock-arm");
            writeElf(bedrockArm, 183);
            check(ServerCommandResolver.readElfMachine(bedrockArm) == 183,
                    "AArch64 ELF machine detection failed");

            File x64Dir = new File(root, "x64-server");
            check(x64Dir.mkdir(), "cannot create x64 fixture");
            writeElf(new File(x64Dir, "bedrock_server"), 62);
            check(ServerCommandResolver.resolve(x64Dir).contains("box64 ./bedrock_server"),
                    "x86-64 Bedrock command must invoke Box64");

            File armDir = new File(root, "arm-server");
            check(armDir.mkdir(), "cannot create arm fixture");
            writeElf(new File(armDir, "bedrock_server"), 183);
            check(ServerCommandResolver.resolve(armDir).contains("./bedrock_server"),
                    "AArch64 Bedrock should run natively");
            check(!ServerCommandResolver.resolve(armDir).contains("box64"),
                    "AArch64 Bedrock must not be routed through Box64");

            File jarDir = new File(root, "java-server");
            check(jarDir.mkdir(), "cannot create Java fixture");
            check(new File(jarDir, "test server.jar").createNewFile(), "cannot create jar fixture");
            check(ServerCommandResolver.resolve(jarDir).equals("java -jar 'test server.jar'"),
                    "JAR filename shell quoting failed");
            check(new File(jarDir, "libraries.jar").createNewFile(), "cannot create second jar fixture");
            boolean ambiguousJarRejected = false;
            try { ServerCommandResolver.resolve(jarDir); }
            catch (java.io.IOException expected) {
                ambiguousJarRejected = expected.getMessage().contains("Multiple JAR");
            }
            check(ambiguousJarRejected, "ambiguous Java server folder must not pick an arbitrary JAR");

            File scriptDir = new File(root, "script-server");
            check(scriptDir.mkdir(), "cannot create script fixture");
            check(new File(scriptDir, "start.sh").createNewFile(), "cannot create script fixture");
            check(ServerCommandResolver.resolve(scriptDir).equals("bash ./start.sh"),
                    "custom start.sh should have priority");

            System.out.println("PASS: ELF architecture and default command resolution");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
