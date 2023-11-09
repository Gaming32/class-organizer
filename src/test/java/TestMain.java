import io.github.gaming32.classorganizer.ClassOrganizeMap;
import io.github.gaming32.classorganizer.ClassOrganizer;

import java.io.IOException;
import java.nio.file.Path;

public class TestMain {
    public static void main(String[] args) throws IOException {
        // TODO: Test on Minecraft?
        final ClassOrganizeMap map = ClassOrganizer.organize(Path.of("build/classes/java/main"));
        for (final String clazz : map.classSet()) {
            System.out.println(clazz + " is in package " + toPackageName(map.getPackage(clazz)));
        }
    }

    // https://stackoverflow.com/a/182924/8840278
    public static String toPackageName(int id) {
        id++;
        final StringBuilder result = new StringBuilder();
        while (id != 0) {
            final int modulo = (id - 1) % 26;
            result.append((char)('a' + modulo));
            id = (id - modulo) / 26;
        }
        return result.reverse().toString();
    }
}
