package ru.big.town.anative;

/** A short tap cancels even a host launch which has not reached onCreate yet. */
final class ClusterMediaSession {
    private long generation;
    private String packageName;

    synchronized long begin(String pkg) {
        packageName = pkg;
        return ++generation;
    }

    synchronized boolean owns(long token, String pkg) {
        return token == generation && pkg != null && pkg.equals(packageName);
    }

    synchronized boolean cancelPackage(String pkg) {
        if (pkg == null || !pkg.equals(packageName)) return false;
        packageName = null;
        generation++;
        return true;
    }

    synchronized void end(long token) {
        if (generation == token) {
            packageName = null;
            generation++;
        }
    }
}
