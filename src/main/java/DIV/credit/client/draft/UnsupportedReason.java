package DIV.credit.client.draft;

/**
 * 編集不可カテゴリの理由分類。BuilderScreen で ? icon hover tooltip に流す。
 * 翻訳キーは `gui.credit.unsupported.<lowercase enum name>`。
 */
public enum UnsupportedReason {
    /** 情報・診断ページ。レシピではない。 */
    VIEWER,
    /** 該当 mod の KubeJS schema が存在しないため emit 不可。 */
    NO_KUBEJS,
    /** vanilla recipe を mod 機械で表示してるだけ。元 mod のカテゴリで編集すべき。 */
    VANILLA_ROUTED,
    /** 規則ベースで動的に派生する recipe (KubeJS では介入できない種類)。 */
    DYNAMIC,
    /** 内部用 type で player は直接書かない。 */
    INTERNAL,
    /** 構造が複雑で UI 編集が現状未実装。 */
    COMPLEX,
    /** 拡張形式で対応難 / 将来対応予定。 */
    DEFERRED;

    public String translationKey() {
        return "gui.credit.unsupported." + name().toLowerCase();
    }
}
