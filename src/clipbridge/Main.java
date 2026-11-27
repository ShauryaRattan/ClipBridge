package clipbridge;

import clipbridge.core.*;
import clipbridge.gui.AppLauncher;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point.   java -jar clipbridge.jar [options]
 *
 *   --name NAME             name shown to the other computers
 *   --port N                TCP port (default 5005)
 *   --data-dir FOLDER       where settings and history are kept
 *   --clipboard-file FILE   use a text file instead of the real clipboard (to run two copies on one PC)
 *   --no-discovery          do not search the network automatically
 *   --help
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Path dataDir = defaultDataDir();
        String name = null;
        Integer port = null;
        Path clipFile = null;
        boolean discovery = true;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--name":
                        name = value(args, ++i, "--name");
                        break;
                    case "--port":
                        port = Integer.parseInt(value(args, ++i, "--port"));
                        if (port < 1 || port > 65535) {
                            throw new IllegalArgumentException("--port must be from 1 to 65535");
                        }
                        break;
                    case "--data-dir":
                        dataDir = Paths.get(value(args, ++i, "--data-dir"));
                        break;
                    case "--clipboard-file":
                        clipFile = Paths.get(value(args, ++i, "--clipboard-file"));
                        break;
                    case "--no-discovery":
                        discovery = false;
                        break;
                    case "--help":
                    case "-h":
                        System.out.println("Usage: java -jar clipbridge.jar [--name NAME] [--port N] [--data-dir FOLDER]\n"
                                + "                               [--clipboard-file FILE] [--no-discovery]");
                        return;
                    default:
                        throw new IllegalArgumentException("Unknown option " + args[i] + " (try --help)");
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("--port must be a number");
            System.exit(2);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
        AppLauncher.launch(dataDir, name, port, clipFile, discovery);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    /** %APPDATA%\ClipBridge on Windows, ~/.clipbridge elsewhere. */
    static Path defaultDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            return Paths.get(appData, Branding.APP_NAME);
        }
        return Paths.get(System.getProperty("user.home"), ".clipbridge");
    }
}
