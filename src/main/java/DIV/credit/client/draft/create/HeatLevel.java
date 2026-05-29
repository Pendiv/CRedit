package DIV.credit.client.draft.create;

/**
 * Create の HeatCondition (NONE / HEATED / SUPERHEATED) に対応する 3 値 enum。
 * mixing / compacting / packing recipe に適用。
 *
 * KubeJS schema 上のフィールド名は "heatRequirement"、値は lowercase ("heated" / "superheated")。
 * NONE は出力しない (Create serializer の default = NONE)。
 */
public enum HeatLevel {
    NONE,
    HEATED,
    SUPERHEATED;

    public HeatLevel cycle() {
        return switch (this) {
            case NONE        -> HEATED;
            case HEATED      -> SUPERHEATED;
            case SUPERHEATED -> NONE;
        };
    }

    /** KubeJS / JSON 出力用 lowercase 名。NONE は空文字 (= 省略推奨)。 */
    public String emitName() {
        return switch (this) {
            case NONE        -> "";
            case HEATED      -> "heated";
            case SUPERHEATED -> "superheated";
        };
    }
}
