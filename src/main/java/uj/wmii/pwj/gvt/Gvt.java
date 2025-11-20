package uj.wmii.pwj.gvt;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Optional;

public class Gvt {

    private final ExitHandler exitHandlerRef;
    private final Path projectRoot;
    private final Path gvtDir;

    private static final String GVT_FOLDER_NAME = ".gvt";
    private static final String VERSION_MESSAGE_FILE = ".gvt_commit_msg";
    private static final String LATEST_VER_FILE = ".gvt_latest_ver";
    private static final String ACTIVE_VER_FILE = ".gvt_active_ver";

    private static final int EXIT_CODE_SUCCESS = 0;
    private static final int EXIT_CODE_MISSING_COMMAND = 1;
    private static final int EXIT_CODE_UNINITIALIZED = -2;
    private static final int EXIT_CODE_SYSTEM_ERROR = -3;
    private static final int EXIT_CODE_UNKNOWN_COMMAND = 1;

    private static final int EXIT_CODE_ALREADY_INITIALIZED = 10;

    private static final int EXIT_CODE_ADD_MISSING_FILE = 20;
    private static final int EXIT_CODE_ADD_FILE_NOT_FOUND = 21;
    private static final int EXIT_CODE_ADD_IO_ERROR = 22;

    private static final int EXIT_CODE_DETACH_MISSING_FILE = 30;
    private static final int EXIT_CODE_DETACH_IO_ERROR = 31;

    private static final int EXIT_CODE_COMMIT_MISSING_FILE = 50;
    private static final int EXIT_CODE_COMMIT_FILE_NOT_FOUND = 51;
    private static final int EXIT_CODE_COMMIT_IO_ERROR = 52;

    private static final int EXIT_CODE_INVALID_VERSION = 60;

    public Gvt(ExitHandler exitHandler) {
        this.exitHandlerRef = exitHandler;
        this.projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        this.gvtDir = this.projectRoot.resolve(GVT_FOLDER_NAME);
    }

    public static void main(String... args) {
        new Gvt(new ExitHandler()).mainInternal(args);
    }

    void mainInternal(String... args) {
        if (args.length == 0) {
            exitHandlerRef.exit(EXIT_CODE_MISSING_COMMAND, "Please specify command.");
            return;
        }

        String command = args[0].toLowerCase();
        String[] params = Arrays.copyOfRange(args, 1, args.length);

        if (!"init".equals(command) && !isInitialized()) {
            exitHandlerRef.exit(EXIT_CODE_UNINITIALIZED, "Current directory is not initialized. Please use \"init\" command to initialize.");
            return;
        }

        try {
            switch (command) {
                case "init":
                    handleInit();
                    break;
                case "add":
                    handleAdd(params);
                    break;
                case "detach":
                    handleDetach(params);
                    break;
                case "commit":
                    handleCommit(params);
                    break;
                case "checkout":
                    handleCheckout(params);
                    break;
                case "history":
                    handleHistory(params);
                    break;
                case "version":
                    handleVersion(params);
                    break;
                default:
                    exitHandlerRef.exit(EXIT_CODE_UNKNOWN_COMMAND, "Unknown command " + args[0] + ".");
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandlerRef.exit(EXIT_CODE_SYSTEM_ERROR, "Underlying system problem. See ERR for details.");
        }
    }

    private boolean isInitialized() {
        return Files.isDirectory(gvtDir);
    }

    private void handleInit() throws IOException {
        if (isInitialized()) {
            exitHandlerRef.exit(EXIT_CODE_ALREADY_INITIALIZED, "Current directory is already initialized.");
            return;
        }
        Files.createDirectory(gvtDir);

        final int initialVersion = 0;
        Path v0 = gvtDir.resolve(String.valueOf(initialVersion));
        Files.createDirectory(v0);
        Files.writeString(v0.resolve(VERSION_MESSAGE_FILE), "GVT initialized.");

        Files.writeString(gvtDir.resolve(LATEST_VER_FILE), String.valueOf(initialVersion));
        Files.writeString(gvtDir.resolve(ACTIVE_VER_FILE), String.valueOf(initialVersion));

        exitHandlerRef.exit(EXIT_CODE_SUCCESS, "Current directory initialized successfully.");
    }

    private void handleAdd(String... params) {
        Optional<String> fileNameOpt = getTargetFileName(params);
        if (fileNameOpt.isEmpty()) {
            exitHandlerRef.exit(EXIT_CODE_ADD_MISSING_FILE, "Please specify file to add.");
            return;
        }
        String fileName = fileNameOpt.get();
        Path file = projectRoot.resolve(fileName);

        if (!Files.exists(file)) {
            exitHandlerRef.exit(EXIT_CODE_ADD_FILE_NOT_FOUND, "File not found. File: " + fileName);
            return;
        }

        try {
            int latestVersion = getLatestVersion();
            Path latestVersionDir = getVersionDirectory(latestVersion);

            if (Files.exists(latestVersionDir.resolve(fileName))) {
                exitHandlerRef.exit(EXIT_CODE_SUCCESS, "File already added. File: " + fileName);
                return;
            }

            int newVersion = latestVersion + 1;
            Path newVersionDir = createVersionDirectory(newVersion, latestVersionDir);
            Files.copy(file, newVersionDir.resolve(fileName));

            String defaultMessage = "File added successfully. File: " + fileName;
            String commitMessage = buildCommitMessage(defaultMessage, params);

            finalizeNewVersion(newVersion, commitMessage);
            exitHandlerRef.exit(EXIT_CODE_SUCCESS, "File added successfully. File: " + fileName);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandlerRef.exit(EXIT_CODE_ADD_IO_ERROR, "File cannot be added. See ERR for details. File: " + fileName);
        }
    }

    private void handleDetach(String... params) {
        Optional<String> fileNameOpt = getTargetFileName(params);
        if (fileNameOpt.isEmpty()) {
            exitHandlerRef.exit(EXIT_CODE_DETACH_MISSING_FILE, "Please specify file to detach.");
            return;
        }
        String fileName = fileNameOpt.get();

        try {
            int latestVersion = getLatestVersion();
            Path latestVersionDir = getVersionDirectory(latestVersion);

            if (!Files.exists(latestVersionDir.resolve(fileName))) {
                exitHandlerRef.exit(EXIT_CODE_SUCCESS, "File is not added to gvt. File: " + fileName);
                return;
            }

            int newVersion = latestVersion + 1;
            Path newVersionDir = createVersionDirectory(newVersion, latestVersionDir);

            Files.delete(newVersionDir.resolve(fileName));

            String defaultMessage = "File detached successfully. File: " + fileName;
            String commitMessage = buildCommitMessage(defaultMessage, params);

            finalizeNewVersion(newVersion, commitMessage);
            exitHandlerRef.exit(EXIT_CODE_SUCCESS, "File detached successfully. File: " + fileName);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandlerRef.exit(EXIT_CODE_DETACH_IO_ERROR, "File cannot be detached, see ERR for details. File: " + fileName);
        }
    }

    private void handleCommit(String... params) {
        Optional<String> fileNameOpt = getTargetFileName(params);
        if (fileNameOpt.isEmpty()) {
            exitHandlerRef.exit(EXIT_CODE_COMMIT_MISSING_FILE, "Please specify file to commit.");
            return;
        }
        String fileName = fileNameOpt.get();
        Path file = projectRoot.resolve(fileName);

        if (!Files.exists(file)) {
            exitHandlerRef.exit(EXIT_CODE_COMMIT_FILE_NOT_FOUND, "File not found. File: " + fileName);
            return;
        }

        try {
            int latestVersion = getLatestVersion();
            Path latestVersionDir = getVersionDirectory(latestVersion);

            if (!Files.exists(latestVersionDir.resolve(fileName))) {
                exitHandlerRef.exit(EXIT_CODE_SUCCESS, "File is not added to gvt. File: " + fileName);
                return;
            }

            int newVersion = latestVersion + 1;
            Path newVersionDir = createVersionDirectory(newVersion, latestVersionDir);

            Files.copy(file, newVersionDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);

            String defaultMessage = "File committed successfully. File: " + fileName;
            String commitMessage = buildCommitMessage(defaultMessage, params);

            finalizeNewVersion(newVersion, commitMessage);
            exitHandlerRef.exit(EXIT_CODE_SUCCESS, "File committed successfully. File: " + fileName);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandlerRef.exit(EXIT_CODE_COMMIT_IO_ERROR, "File cannot be committed, see ERR for details. File: " + fileName);
        }
    }

    private void handleCheckout(String... params) throws IOException {
        int version;
        try {
            if (params.length != 1) {
                throw new NumberFormatException();
            }
            version = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            exitHandlerRef.exit(EXIT_CODE_INVALID_VERSION, "Invalid version number: " + (params.length > 0 ? params[0] : ""));
            return;
        }

        if (version < 0 || version > getLatestVersion()) {
            exitHandlerRef.exit(EXIT_CODE_INVALID_VERSION, "Invalid version number: " + version);
            return;
        }

        Path targetVersionDir = getVersionDirectory(version);
        Path activeVersionDir = getVersionDirectory(getActiveVersion());

        cleanWorkingDirectory(activeVersionDir);
        copyDirectoryContents(targetVersionDir, projectRoot);
        setActiveVersion(version);

        exitHandlerRef.exit(EXIT_CODE_SUCCESS, "Checkout successful for version: " + version);
    }

    private void handleHistory(String... params) throws IOException {
        int latestVersion = getLatestVersion();
        int limit = latestVersion + 1;

        if (params.length == 2 && "-last".equals(params[0])) {
            try {
                int requestedLimit = Integer.parseInt(params[1]);
                if (requestedLimit > 0) {
                    limit = requestedLimit;
                }
            } catch (NumberFormatException ignored) {}
        }

        StringBuilder history = new StringBuilder();

        int versionCounter = latestVersion;
        int filesRemaining = limit;

        while (versionCounter >= 0 && filesRemaining > 0) {
            Path versionDir = getVersionDirectory(versionCounter);
            String message = Files.readAllLines(versionDir.resolve(VERSION_MESSAGE_FILE)).getFirst();
            history.append(versionCounter).append(": ").append(message).append("\n");

            versionCounter--;
            filesRemaining--;
        }

        exitHandlerRef.exit(EXIT_CODE_SUCCESS, history.toString());
    }

    private void handleVersion(String... params) throws IOException {
        int version;
        try {
            version = params.length > 0 ? Integer.parseInt(params[0]) : getActiveVersion();
        } catch (NumberFormatException e) {
            exitHandlerRef.exit(EXIT_CODE_INVALID_VERSION, "Invalid version number: " + (params.length > 0 ? params[0] : ""));
            return;
        }

        if (version < 0 || version > getLatestVersion()) {
            exitHandlerRef.exit(EXIT_CODE_INVALID_VERSION, "Invalid version number: " + version);
            return;
        }

        Path versionDir = getVersionDirectory(version);
        String message = Files.readString(versionDir.resolve(VERSION_MESSAGE_FILE));
        exitHandlerRef.exit(EXIT_CODE_SUCCESS, "Version: " + version + "\n" + message);
    }

    private Path getVersionDirectory(int version) {
        return gvtDir.resolve(String.valueOf(version));
    }

    private int getLatestVersion() throws IOException {
        return Integer.parseInt(Files.readString(gvtDir.resolve(LATEST_VER_FILE)).trim());
    }

    private int getActiveVersion() throws IOException {
        return Integer.parseInt(Files.readString(gvtDir.resolve(ACTIVE_VER_FILE)).trim());
    }

    private void setActiveVersion(int version) throws IOException {
        Files.writeString(gvtDir.resolve(ACTIVE_VER_FILE), String.valueOf(version));
    }

    private Path createVersionDirectory(int newVersion, Path sourceDir) throws IOException {
        Path newDir = getVersionDirectory(newVersion);
        Files.createDirectory(newDir);
        copyDirectoryContents(sourceDir, newDir);
        return newDir;
    }

    private void finalizeNewVersion(int version, String message) throws IOException {
        Path versionDir = getVersionDirectory(version);
        Files.writeString(versionDir.resolve(VERSION_MESSAGE_FILE), message);
        Files.writeString(gvtDir.resolve(LATEST_VER_FILE), String.valueOf(version));
        setActiveVersion(version);
    }

    private String buildCommitMessage(String defaultMessage, String[] params) {
        return parseUserMessage(params).orElse(defaultMessage);
    }

    private Optional<String> getTargetFileName(String[] params) {
        if (params.length == 0 || "-m".equals(params[0])) {
            return Optional.empty();
        }
        return Optional.of(params[0]);
    }

    private Optional<String> parseUserMessage(String[] params) {
        if (params.length >= 2 && "-m".equals(params[params.length - 2])) {
            String message = params[params.length - 1];

            if (message.length() >= 2 && message.startsWith("\"") && message.endsWith("\"")) {
                return Optional.of(message.substring(1, message.length() - 1));
            }

            return Optional.of(message);
        }
        return Optional.empty();
    }

    private void copyDirectoryContents(Path source, Path destination) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source, p -> !p.getFileName().toString().startsWith("."))) {
            for (Path p : stream) {
                Files.copy(p, destination.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void cleanWorkingDirectory(Path versionDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionDir, p -> !p.getFileName().toString().startsWith("."))) {
            for (Path p : stream) {
                Files.deleteIfExists(projectRoot.resolve(p.getFileName()));
            }
        }
    }
}