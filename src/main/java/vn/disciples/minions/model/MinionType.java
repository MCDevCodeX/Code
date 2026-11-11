package vn.disciples.minions.model;

public enum MinionType {
    FARM,
    MINER;

    public String displayName() {
        return this == FARM ? "Nông Sản" : "Khoáng Sản";
    }
}
