package DIV.credit.client.importer;

import DIV.credit.Credit;
import DIV.credit.client.draft.CraftingDraft;
import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v2.1.2: import 元のコードを現行 Draft.emit() で再生成するためのパイプライン。
 *
 * <h3>Phase 1 対応範囲</h3>
 * <ul>
 *   <li>{@code event.custom({type:..., ...}).id('...');} 形式のみ</li>
 *   <li>vanilla shaped / shapeless (event.shaped/.shapeless) は未対応 (Phase 2)</li>
 *   <li>{@code event.remove(...)} (DELETE) は変換不要 → caller 側で copy-thru</li>
 * </ul>
 *
 * <h3>失敗ケース (Optional.empty を返す)</h3>
 * <ul>
 *   <li>JSON 抽出失敗</li>
 *   <li>type field 欠如 / RecipeSerializer 未登録</li>
 *   <li>Recipe.fromJson 例外</li>
 *   <li>JEI runtime 未準備</li>
 *   <li>Recipe → JEI category 解決失敗</li>
 *   <li>Draft 未対応 / loadFromRecipe 失敗 / emit null</li>
 * </ul>
 */
public final class RecipeReEmitter {

    private RecipeReEmitter() {}

    /**
     * 1 ImportedRecipe を再 emit。成功時は新 codeBody (末尾 \n 付き) を返す。
     * 失敗時 Optional.empty。
     */
    public static Optional<String> reEmit(ImportedRecipe r) {
        if (r.kind() == OperationKind.DELETE) return Optional.empty(); // caller: copy-thru
        // Phase 2: vanilla shaped/shapeless は CraftingDraft 経由で直接再生成
        if ("shaped".equals(r.recipeType()) || "shapeless".equals(r.recipeType())) {
            return reEmitVanillaCrafting(r);
        }
        if (!"custom".equals(r.recipeType())) return Optional.empty();

        // v3.0.0-P1: JSON → Recipe<?> 復元は ImportedRecipeRecovery に委譲
        Optional<Recipe<?>> recovered = ImportedRecipeRecovery.recover(r);
        if (recovered.isEmpty()) return Optional.empty();
        Recipe<?> recipe = recovered.get();

        // v3.0.0-P1: Recipe → category 検索は JeiRenderBridge に委譲
        IRecipeCategory<?> category = DIV.credit.client.preview.JeiRenderBridge.findCategoryForRecipe(recipe);
        if (category == null) {
            Credit.LOGGER.debug("[CraftPattern] re-emit: category 解決失敗 {}", r.recipeId());
            return Optional.empty();
        }

        // v3.0.0-P1: drawable 構築も JeiRenderBridge に委譲
        IRecipeLayoutDrawable<?> drawable = DIV.credit.client.preview.JeiRenderBridge.build(category, recipe);
        if (drawable == null) {
            Credit.LOGGER.debug("[CraftPattern] re-emit: drawable 作成失敗 {}", r.recipeId());
            return Optional.empty();
        }

        // Draft (テンポラリ DraftStore、BuilderScreen の state を汚さない)
        DraftStore tempStore = new DraftStore();
        RecipeDraft draft = tempStore.getOrCreate(category);
        if (draft == null) {
            Credit.LOGGER.debug("[CraftPattern] re-emit: Draft 未対応 {} (category={})",
                r.recipeId(), category.getRecipeType().getUid());
            return Optional.empty();
        }

        if (!draft.loadFromRecipe(drawable)) {
            Credit.LOGGER.debug("[CraftPattern] re-emit: Draft.loadFromRecipe 失敗 {}", r.recipeId());
            return Optional.empty();
        }

        String emitted = draft.emit(r.recipeId());
        if (emitted == null || emitted.isBlank()) {
            Credit.LOGGER.debug("[CraftPattern] re-emit: Draft.emit null {}", r.recipeId());
            return Optional.empty();
        }
        return Optional.of(emitted);
    }

    /** 開き括弧位置から対応する閉じ括弧位置を探す (文字列/コメント無視)。失敗時 -1。 */
    private static int findMatching(String s, int openPos, char open, char close) {
        int depth = 0;
        int p = openPos;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\'' || c == '"') { p = skipString(s, p); continue; }
            if (p + 1 < s.length() && c == '/' && s.charAt(p + 1) == '/') {
                int eol = s.indexOf('\n', p);
                p = eol < 0 ? s.length() : eol;
                continue;
            }
            if (c == open) depth++;
            else if (c == close) { depth--; if (depth == 0) return p; }
            p++;
        }
        return -1;
    }

    private static int skipString(String s, int start) {
        char q = s.charAt(start);
        int p = start + 1;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\\') { p += 2; continue; }
            if (c == q) return p + 1;
            p++;
        }
        return s.length();
    }

    // v3.0.0-P1: findCategoryForRecipe / createDrawable は JeiRenderBridge に移動済。
    // 本クラスでは reEmit() から JeiRenderBridge.findCategoryForRecipe / build を呼ぶ。

    // ─── Phase 2: vanilla shaped / shapeless ───

    /**
     * event.shaped/.shapeless の codeBody を mini-parse → CraftingDraft に slot 設定 → emit。
     * JSON 中継しないので Item.of(...).strongNBT() (Singularity 等) もそのまま保持できる。
     */
    private static Optional<String> reEmitVanillaCrafting(ImportedRecipe r) {
        CraftingDraft draft = buildVanillaCraftingDraft(r);
        if (draft == null) return Optional.empty();
        String emitted = draft.emit(r.recipeId());
        if (emitted == null || emitted.isBlank()) {
            Credit.LOGGER.debug("[CraftPattern] re-emit ({}): CraftingDraft.emit null {}", r.recipeType(), r.recipeId());
            return Optional.empty();
        }
        return Optional.of(emitted);
    }

    /**
     * v3.2: event.shaped/.shapeless の codeBody から CraftingDraft (slot + mode) を構築。
     * <p>{@link ImportedRecipeRecovery} からも呼ばれる (= preview 用に Recipe<?> 復元する経路)。
     * @return 構築済 CraftingDraft、 parse 失敗時 null
     */
    @Nullable
    public static CraftingDraft buildVanillaCraftingDraft(ImportedRecipe r) {
        boolean shaped = "shaped".equals(r.recipeType());
        if (!shaped && !"shapeless".equals(r.recipeType())) return null;

        String args = extractCallArgs(r.codeBody(), shaped ? "event.shaped" : "event.shapeless");
        if (args == null) return null;

        List<String> topArgs = splitTopLevelArgs(args);
        if (topArgs.size() < 2) return null;

        IngredientSpec output = parseItemSpec(topArgs.get(0).trim());
        if (output == null || output.isEmpty()) {
            Credit.LOGGER.debug("[CraftPattern] buildVanillaCraftingDraft ({}): output parse 失敗 {}", r.recipeType(), r.recipeId());
            return null;
        }

        CraftingDraft draft = new CraftingDraft();
        draft.setSlot(CraftingDraft.IDX_OUTPUT, output);

        if (shaped) {
            if (topArgs.size() < 3) return null;
            List<String> pattern = parseStringArray(topArgs.get(1).trim());
            Map<Character, IngredientSpec> key = parseKeyObject(topArgs.get(2).trim());
            if (pattern == null || key == null) {
                Credit.LOGGER.debug("[CraftPattern] buildVanillaCraftingDraft (shaped): pattern/key parse 失敗 {}", r.recipeId());
                return null;
            }
            draft.setMode(CraftingDraft.Mode.SHAPED);
            for (int row = 0; row < pattern.size() && row < CraftingDraft.HEIGHT; row++) {
                String rowStr = pattern.get(row);
                for (int col = 0; col < rowStr.length() && col < CraftingDraft.WIDTH; col++) {
                    char ch = rowStr.charAt(col);
                    if (ch == ' ') continue;
                    IngredientSpec spec = key.get(ch);
                    if (spec == null) continue;
                    draft.setSlot(CraftingDraft.IDX_INPUT_0 + row * CraftingDraft.WIDTH + col, spec);
                }
            }
        } else {
            List<String> ingTokens = parseTopArray(topArgs.get(1).trim());
            if (ingTokens == null) return null;
            draft.setMode(CraftingDraft.Mode.SHAPELESS);
            for (int i = 0; i < ingTokens.size() && i < CraftingDraft.INPUT_COUNT; i++) {
                IngredientSpec spec = parseItemSpec(ingTokens.get(i).trim());
                if (spec == null) continue;
                draft.setSlot(CraftingDraft.IDX_INPUT_0 + i, spec);
            }
        }
        return draft;
    }

    /** "event.shaped(...)" の (...) 内 (括弧バランス考慮) を返す。 */
    @Nullable
    static String extractCallArgs(String code, String callPrefix) {
        int idx = code.indexOf(callPrefix);
        if (idx < 0) return null;
        int paren = code.indexOf('(', idx + callPrefix.length());
        if (paren < 0) return null;
        int close = findMatching(code, paren, '(', ')');
        if (close < 0) return null;
        return code.substring(paren + 1, close);
    }

    /** トップレベルの "," で分割 (括弧/文字列内は無視)。 */
    static List<String> splitTopLevelArgs(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int p = 0;
        while (p < args.length()) {
            char c = args.charAt(p);
            if (c == '\'' || c == '"') { p = skipString(args, p); continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(args.substring(start, p));
                start = p + 1;
            }
            p++;
        }
        if (start < args.length()) {
            String tail = args.substring(start).trim();
            if (!tail.isEmpty()) out.add(args.substring(start));
        }
        return out;
    }

    /**
     * KubeJS item spec ('ns:path' / "#ns:tag" / Item.of(...)) を IngredientSpec に。
     * 認識できなければ null。
     */
    @Nullable
    static IngredientSpec parseItemSpec(String token) {
        token = token.trim();
        if (token.isEmpty()) return null;

        // 'ns:path' or "#ns:tag"
        if ((token.startsWith("'") || token.startsWith("\""))
            && (token.endsWith("'") || token.endsWith("\""))) {
            String inner = token.substring(1, token.length() - 1);
            if (inner.startsWith("#")) {
                ResourceLocation rl = parseRl(inner.substring(1));
                return rl != null ? new IngredientSpec.Tag(rl) : null;
            }
            ResourceLocation rl = parseRl(inner);
            if (rl == null) return null;
            Item item = BuiltInRegistries.ITEM.get(rl);
            return new IngredientSpec.Item(new ItemStack(item, 1));
        }

        // Item.of(...).strongNBT() or Item.of(...)
        if (token.startsWith("Item.of(")) {
            int parenStart = "Item.of".length();
            int parenEnd = findMatching(token, parenStart, '(', ')');
            if (parenEnd < 0) return null;
            List<String> ofArgs = splitTopLevelArgs(token.substring(parenStart + 1, parenEnd));
            if (ofArgs.isEmpty()) return null;

            String idArg = ofArgs.get(0).trim();
            if (!((idArg.startsWith("'") || idArg.startsWith("\""))
                && (idArg.endsWith("'") || idArg.endsWith("\"")))) return null;
            String inner = idArg.substring(1, idArg.length() - 1);
            ResourceLocation rl = parseRl(inner);
            if (rl == null) return null;
            Item item = BuiltInRegistries.ITEM.get(rl);

            int count = 1;
            String nbtStr = null;
            for (int i = 1; i < ofArgs.size(); i++) {
                String a = ofArgs.get(i).trim();
                if (a.matches("\\d+")) {
                    count = Integer.parseInt(a);
                } else if ((a.startsWith("'") || a.startsWith("\""))
                    && (a.endsWith("'") || a.endsWith("\""))) {
                    String raw = a.substring(1, a.length() - 1);
                    // unescape: \\ → \, \' → '
                    nbtStr = raw.replace("\\\\", "\\").replace("\\'", "'");
                }
            }
            ItemStack stack = new ItemStack(item, count);
            if (nbtStr != null) {
                try { stack.setTag(TagParser.parseTag(nbtStr)); }
                catch (Exception e) {
                    Credit.LOGGER.debug("[CraftPattern] re-emit: NBT parse 失敗 ({}): {}", nbtStr, e.getMessage());
                }
            }
            return new IngredientSpec.Item(stack);
        }

        return null;
    }

    @Nullable
    private static ResourceLocation parseRl(String s) {
        try { return new ResourceLocation(s); } catch (Exception e) { return null; }
    }

    /** [ 'ABA', 'CBC' ] → List<String>。 */
    @Nullable
    private static List<String> parseStringArray(String token) {
        token = token.trim();
        if (!token.startsWith("[") || !token.endsWith("]")) return null;
        String inner = token.substring(1, token.length() - 1);
        List<String> out = new ArrayList<>();
        for (String p : splitTopLevelArgs(inner)) {
            String t = p.trim();
            if ((t.startsWith("'") && t.endsWith("'"))
                || (t.startsWith("\"") && t.endsWith("\""))) {
                out.add(t.substring(1, t.length() - 1));
            }
        }
        return out;
    }

    /** {A: spec, B: spec} → Map<Character, IngredientSpec>。 */
    @Nullable
    private static Map<Character, IngredientSpec> parseKeyObject(String token) {
        token = token.trim();
        if (!token.startsWith("{") || !token.endsWith("}")) return null;
        String inner = token.substring(1, token.length() - 1);
        Map<Character, IngredientSpec> out = new HashMap<>();
        for (String entry : splitTopLevelArgs(inner)) {
            int colon = findTopLevelColon(entry);
            if (colon < 0) continue;
            String k = entry.substring(0, colon).trim();
            String v = entry.substring(colon + 1).trim();
            char kc = extractCharKey(k);
            if (kc == 0) continue;
            IngredientSpec spec = parseItemSpec(v);
            if (spec != null) out.put(kc, spec);
        }
        return out.isEmpty() ? null : out;
    }

    /** [ spec, spec, ... ] → raw token list (各要素は parseItemSpec に渡す)。 */
    @Nullable
    private static List<String> parseTopArray(String token) {
        token = token.trim();
        if (!token.startsWith("[") || !token.endsWith("]")) return null;
        return splitTopLevelArgs(token.substring(1, token.length() - 1));
    }

    private static int findTopLevelColon(String s) {
        int depth = 0;
        int p = 0;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\'' || c == '"') { p = skipString(s, p); continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ':' && depth == 0) return p;
            p++;
        }
        return -1;
    }

    private static char extractCharKey(String k) {
        if (k.isEmpty()) return 0;
        if (k.length() == 1) return k.charAt(0);
        if ((k.startsWith("'") || k.startsWith("\"")) && k.length() == 3) return k.charAt(1);
        return 0;
    }
}
