package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

public enum UserRole {
    USER,
    ADMIN_MIASTA,
    ADMIN_GMINY,
    ADMIN_POWIATU,
    ADMIN_WOJEWODZTWA,
    SUPER_ADMIN;

    public boolean isAdmin() {
        return this != USER;
    }

    /**
     * Returns true if this role is allowed to assign {@code target} role to a new user.
     * Rules:
     * - USER cannot assign any role.
     * - No one can assign SUPER_ADMIN (must be done via DB bootstrap).
     * - No one can assign USER (use revokeAdmin for that).
     * - SUPER_ADMIN can assign any admin role.
     * - Other admins can only assign roles strictly lower in the hierarchy (lower ordinal).
     */
    public boolean canAssignRole(UserRole target) {
        if (!isAdmin() || target == USER || target == SUPER_ADMIN) return false;
        if (this == SUPER_ADMIN) return true;
        return this.ordinal() > target.ordinal();
    }
}
