package io.github.gaming32.classorganizer;

import java.util.*;

public class ClassOrganizeMap {
    private final Map<String, Integer> classToPackage = new HashMap<>();
    private final Map<Integer, Set<String>> packageToClasses = new HashMap<>();

    public ClassOrganizeMap(Map<String, Integer> classToPackage) {
        this.classToPackage.putAll(classToPackage);
        for (final var entry : classToPackage.entrySet()) {
            packageToClasses.computeIfAbsent(entry.getValue(), k -> new HashSet<>()).add(entry.getKey());
        }
    }

    public ClassOrganizeMap() {
    }

    public void addClass(String clazz) {
        addClass(clazz, classToPackage.size());
    }

    public void addClass(String clazz, int pkg) {
        addClass(clazz, Integer.valueOf(pkg));
    }

    void addClass(String clazz, Integer pkg) {
        final Integer oldPackage = classToPackage.put(clazz, pkg);
        if (pkg.equals(oldPackage)) return;
        maybeRemoveFrom(clazz, oldPackage);
        packageToClasses.computeIfAbsent(pkg, k -> new HashSet<>()).add(clazz);
    }

    public void removeClass(String clazz) {
        maybeRemoveFrom(clazz, classToPackage.remove(clazz));
    }

    private void maybeRemoveFrom(String clazz, Integer oldPackage) {
        if (oldPackage != null) {
            final Set<String> oldClasses = packageToClasses.get(oldPackage);
            oldClasses.remove(clazz);
            if (oldClasses.isEmpty()) {
                packageToClasses.remove(oldPackage);
            }
        }
    }

    public Integer getPackage(String clazz) {
        return classToPackage.get(clazz);
    }

    public Set<String> getClasses(int pkg) {
        final Set<String> classes = packageToClasses.get(pkg);
        return classes != null ? Collections.unmodifiableSet(classes) : Collections.emptySet();
    }

    public boolean containsClass(String clazz) {
        return classToPackage.containsKey(clazz);
    }

    public void mergePackages(String class1, String class2) {
        final Integer pkg1 = classToPackage.get(class1);
        if (pkg1 == null) {
            throw new IllegalArgumentException(class1 + " not in this map");
        }
        final Integer pkg2 = classToPackage.get(class2);
        if (pkg2 == null) {
            throw new IllegalArgumentException(class1 + " not in this map");
        }
        if (pkg1.equals(pkg2)) return;
        mergePackages(pkg1, pkg2);
    }

    public void mergePackages(int pkg1, int pkg2) {
        if (pkg1 == pkg2) return;
        mergePackages(Integer.valueOf(pkg1), Integer.valueOf(pkg2));
    }

    void mergePackages(Integer pkg1, Integer pkg2) {
        final Set<String> classesIn2 = packageToClasses.remove(pkg2);
        packageToClasses.get(pkg1).addAll(classesIn2);
        for (final String classIn2 : classesIn2) {
            classToPackage.put(classIn2, pkg1);
        }
    }

    public int classCount() {
        return classToPackage.size();
    }

    public int packageCount() {
        return packageToClasses.size();
    }

    public Set<String> classSet() {
        return Collections.unmodifiableSet(classToPackage.keySet());
    }

    public Set<Integer> packageSet() {
        return Collections.unmodifiableSet(packageToClasses.keySet());
    }

    public ClassOrganizeMap compacted() {
        final Map<Integer, Integer> remap = new HashMap<>();
        for (final Integer pkg : packageToClasses.keySet()) {
            remap.put(pkg, remap.size());
        }
        final ClassOrganizeMap result = new ClassOrganizeMap();
        for (final var entry : packageToClasses.entrySet()) {
            final Integer remapped = remap.get(entry.getKey());
            for (final String clazz : entry.getValue()) {
                result.classToPackage.put(clazz, remapped);
            }
            result.packageToClasses.put(remapped, new HashSet<>(entry.getValue()));
        }
        return result;
    }
}
