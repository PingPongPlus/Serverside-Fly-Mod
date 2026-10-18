package pingplus.flyhacks;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class FlyConfig {
    private final Path path;
    private boolean operatorsOnly;

    FlyConfig(Path path) throws IOException {
        this.path = path;
        if (Files.exists(path)) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            }
            String value = properties.getProperty("operatorsOnly", "false").trim();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IOException("operatorsOnly must be true or false in " + path);
            }
            operatorsOnly = Boolean.parseBoolean(value);
        }
    }

    boolean operatorsOnly() {
        return operatorsOnly;
    }

    void setOperatorsOnly(boolean value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "flymod-", ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty("operatorsOnly", Boolean.toString(value));
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Fly Mod: true restricts /fly to operators (permission level 2+)");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            operatorsOnly = value;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
