package DIV.credit.client.staging;

import DIV.credit.client.io.ScriptWriter.OperationKind;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 1 つの ADD/EDIT/DELETE 操作。staging キューに積まれて push まで実ファイル書き込みされない。
 * <p>codeBody は emit 時点で生成済の KubeJS コード片。push 時はこれをそのまま該当 .js に追記する。
 */
public final class StagedChange {

    public final String       id;            // UUID 文字列、UI の取捨選択キーに使う
    public final OperationKind kind;
    public final String       modid;          // 出力先 <modid>/{add,edit,delete}.js を決める
    public final String       recipeId;       // 表示 / 追跡用 (ADD/EDIT は新 ID, DELETE は対象 ID)
    @Nullable public final String origRecipeId; // EDIT のみ、置換元の元 ID
    public final String       codeBody;       // KubeJS コード (ScriptWriter ヘッダ・footer 抜きの中身)
    public final long         timestamp;      // ms epoch
    public boolean            committed;      // /credit commit で承認されたか
    /** v2.0.7: 元 JEI カテゴリ UID (例 gtceu:primitive_blast_furnace)。GT 等の ID prefix 解決用。 */
    @Nullable public final String jeiCategoryUid;

    public StagedChange(String id, OperationKind kind, String modid, String recipeId,
                        @Nullable String origRecipeId, String codeBody, long timestamp,
                        boolean committed, @Nullable String jeiCategoryUid) {
        this.id             = id;
        this.kind           = kind;
        this.modid          = modid;
        this.recipeId       = recipeId;
        this.origRecipeId   = origRecipeId;
        this.codeBody       = codeBody;
        this.timestamp      = timestamp;
        this.committed      = committed;
        this.jeiCategoryUid = jeiCategoryUid;
    }

    public static StagedChange create(OperationKind kind, String modid, String recipeId,
                                      @Nullable String origRecipeId, String codeBody,
                                      @Nullable String jeiCategoryUid) {
        return new StagedChange(UUID.randomUUID().toString(), kind, modid, recipeId,
            origRecipeId, codeBody, System.currentTimeMillis(), false, jeiCategoryUid);
    }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putString("id",        id);
        t.putString("kind",      kind.name());
        t.putString("modid",     modid);
        t.putString("recipeId",  recipeId);
        if (origRecipeId != null) t.putString("origRecipeId", origRecipeId);
        t.putString("code",      codeBody);
        t.putLong("ts",          timestamp);
        t.putBoolean("committed", committed);
        if (jeiCategoryUid != null) t.putString("jeiCat", jeiCategoryUid);
        return t;
    }

    @Nullable
    public static StagedChange fromNbt(CompoundTag t) {
        try {
            return new StagedChange(
                t.getString("id"),
                OperationKind.valueOf(t.getString("kind")),
                t.getString("modid"),
                t.getString("recipeId"),
                t.contains("origRecipeId") ? t.getString("origRecipeId") : null,
                t.getString("code"),
                t.getLong("ts"),
                t.getBoolean("committed"),
                t.contains("jeiCat") ? t.getString("jeiCat") : null
            );
        } catch (Exception e) {
            return null;
        }
    }
}
