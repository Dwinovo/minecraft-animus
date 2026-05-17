package com.dwinovo.animus.client.data;

/**
 * Client-side immutable snapshot of one unit slot's metadata + runtime
 * state. Mirrors a subset of the server's {@code UnitConfig} +
 * {@code PlayerAnimusData}: only the fields the PlayerAgent's env block
 * and the GUI need to display.
 *
 * @param unitId    stable 1..6 id
 * @param name      player-set display name; {@code null} when unnamed
 * @param modelKey  bedrock model identifier
 * @param alive     true when not in respawn cooldown
 * @param active    true when the in-world entity is currently spawned
 *                  (i.e. busy with a task)
 */
public record ClientUnitView(int unitId, String name, String modelKey, boolean alive, boolean active) {

    public static ClientUnitView defaultFor(int unitId) {
        return new ClientUnitView(unitId, null, "animus:hachiware", true, false);
    }

    public ClientUnitView withName(String newName) {
        return new ClientUnitView(unitId, newName, modelKey, alive, active);
    }

    public ClientUnitView withModelKey(String newModel) {
        return new ClientUnitView(unitId, name, newModel, alive, active);
    }

    public ClientUnitView withActive(boolean active) {
        return new ClientUnitView(unitId, name, modelKey, alive, active);
    }

    public ClientUnitView withAlive(boolean alive) {
        return new ClientUnitView(unitId, name, modelKey, alive, active);
    }

    public String displayLabel() {
        return name != null && !name.isBlank() ? name : "Unit " + unitId;
    }
}
