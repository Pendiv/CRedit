package DIV.credit.client.draft.de;

/**
 * DE FusionRecipe の tier 表記（DE の TechLevel enum をミラー）。
 * brandonsCore 依存を引かないよう自前 enum + 表示色も保持。
 */
public enum DETechLevel {
    DRACONIUM("Draconium", 0xFF4B95E0),
    WYVERN   ("Wyvern",    0xFFA060CC),
    DRACONIC ("Draconic",  0xFFCC4040),
    CHAOTIC  ("Chaotic",   0xFFE89020);

    public final String displayName;
    public final int color;

    DETechLevel(String n, int c) { this.displayName = n; this.color = c; }

    public DETechLevel cycle() {
        DETechLevel[] v = values();
        return v[(this.ordinal() + 1) % v.length];
    }

    public DETechLevel cycleBack() {
        DETechLevel[] v = values();
        return v[(this.ordinal() + v.length - 1) % v.length];
    }
}
