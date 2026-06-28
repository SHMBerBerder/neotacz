package com.tacz.guns.api.item.runtime;

public record GunHitContext(
        double distanceMeters,
        int penetrationCount,
        double penetrationDamageMultiplier
) {
    public GunHitContext {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0.0d) {
            distanceMeters = -1.0d;
        }
        penetrationCount = Math.max(penetrationCount, 0);
        if (!Double.isFinite(penetrationDamageMultiplier) || penetrationDamageMultiplier < 0.0d) {
            penetrationDamageMultiplier = -1.0d;
        }
    }

    public static GunHitContext unknown() {
        return new GunHitContext(-1.0d, 0, -1.0d);
    }
}
