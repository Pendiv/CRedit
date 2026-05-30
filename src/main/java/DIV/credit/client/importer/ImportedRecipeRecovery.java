package DIV.credit.client.importer;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.0.0 (P1): {@link ImportedRecipe} → {@link Recipe} インスタンス復元。
 * <p>{@link RecipeReEmitter} の前半 flow を独立 utility に抽出した形。
 * import 経路 (RecipeReEmitter) と preview 経路 (ImportedPreviewSource) の双方から
 * 呼ばれる。
 *
 * <h3>対応スコープ (v3.0.0 P1)</h3>
 * <ul>
 *   <li>event.custom({type:..., ...}).id(...) → RecipeSerializer.fromJson 経由で復元</li>
 *   <li>event.shaped / event.shapeless → 現状未対応 (将来 P3 で必要なら追加、当面 empty)</li>
 *   <li>event.remove (DELETE) → recovery 対象外 (empty)</li>
 * </ul>
 *
 * <h3>失敗時</h3>
 * すべて {@link Optional#empty()} 返し + log debug。chat 通知は呼び出し側責務。
 */
public final class ImportedRecipeRecovery {

    /** event.custom( ... ).id('...') の (...) 部分を非貪欲に拾う。 */
    private static final Pattern CUSTOM_OPEN = Pattern.compile("event\\.custom\\s*\\(");

    private ImportedRecipeRecovery() {}

    /**
     * v3.0.0 (S13): spec 互換 alias。Optional unwrap で null 返す。
     * ImportedPreviewSource 等で使う。
     */
    @Nullable
    public static Recipe<?> recoverRecipe(ImportedRecipe r) {
        return recover(r).orElse(null);
    }

    /** 主 entry。 ImportedRecipe → Recipe<?>。 失敗時 empty。 */
    public static Optional<Recipe<?>> recover(ImportedRecipe r) {
        if (r == null) return Optional.empty();
        if (r.kind() == OperationKind.DELETE) return Optional.empty();

        // v3.12-B: mod recipe (= "modid.type" 形式) は EDIT なら orig lookup、 ADD は null (= placeholder)
        if (r.recipeType() != null && r.recipeType().contains(".")) {
            Recipe<?> rec = recoverModRecipe(r);
            if (rec == null) {
                Credit.LOGGER.info("[CraftPattern] recover (mod {}): null (kind={}, id={}, orig={})",
                    r.recipeType(), r.kind(), r.recipeId(), r.origRecipeId());
            }
            return Optional.ofNullable(rec);
        }

        // v3.4-3: vanilla cooking 系 (smelting/blasting/smoking/campfireCooking)
        switch (r.recipeType()) {
            case "smelting", "blasting", "smoking", "campfireCooking" -> {
                Recipe<?> rec = buildVanillaCookingRecipe(r);
                if (rec == null) {
                    Credit.LOGGER.info("[CraftPattern] recover ({}): cooking build null id={}",
                        r.recipeType(), r.recipeId());
                }
                return Optional.ofNullable(rec);
            }
            case "stonecutting" -> {
                Recipe<?> rec = buildVanillaStonecuttingRecipe(r);
                if (rec == null) {
                    Credit.LOGGER.info("[CraftPattern] recover (stonecutting): build null id={}", r.recipeId());
                }
                return Optional.ofNullable(rec);
            }
            case "fuel" -> {
                // JEI fuel は recipe instance が無い (= JEI 独自表示) → preview 対象外
                return Optional.empty();
            }
        }

        // v3.2: shaped/shapeless は RecipeReEmitter の mini-parser → CraftingDraft → toRecipeInstance
        if ("shaped".equals(r.recipeType()) || "shapeless".equals(r.recipeType())) {
            try {
                var draft = RecipeReEmitter.buildVanillaCraftingDraft(r);
                if (draft == null) {
                    Credit.LOGGER.info("[CraftPattern] recover ({}): buildVanillaCraftingDraft null id={}",
                        r.recipeType(), r.recipeId());
                    return Optional.empty();
                }
                Recipe<?> rec = draft.toRecipeInstance();
                if (rec == null) {
                    Credit.LOGGER.info("[CraftPattern] recover ({}): toRecipeInstance null id={}",
                        r.recipeType(), r.recipeId());
                }
                return Optional.ofNullable(rec);
            } catch (Exception e) {
                Credit.LOGGER.info("[CraftPattern] recover ({}): exception id={}: {}",
                    r.recipeType(), r.recipeId(), e.getMessage());
                return Optional.empty();
            }
        }

        if (!"custom".equals(r.recipeType())) {
            Credit.LOGGER.info("[CraftPattern] recover: recipeType '{}' not supported (id={})", r.recipeType(), r.recipeId());
            return Optional.empty();
        }

        String jsonText = extractCustomJsonObject(r.codeBody());
        if (jsonText == null) {
            Credit.LOGGER.info("[CraftPattern] recover: JSON 抽出失敗 id={}, codeBody head={}",
                r.recipeId(),
                r.codeBody() != null && r.codeBody().length() > 80 ? r.codeBody().substring(0, 80) : r.codeBody());
            return Optional.empty();
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(jsonText).getAsJsonObject();
        } catch (Exception e) {
            Credit.LOGGER.debug("[CraftPattern] recover: JSON parse 失敗 {}: {}", r.recipeId(), e.getMessage());
            return Optional.empty();
        }

        if (!json.has("type")) {
            Credit.LOGGER.info("[CraftPattern] recover: type field 欠如 id={}", r.recipeId());
            return Optional.empty();
        }
        ResourceLocation typeRl;
        try { typeRl = ResourceLocation.parse(json.get("type").getAsString()); }
        catch (Exception e) {
            Credit.LOGGER.info("[CraftPattern] recover: type 不正 id={}: {}", r.recipeId(), e.getMessage());
            return Optional.empty();
        }
        RecipeSerializer<?> ser = BuiltInRegistries.RECIPE_SERIALIZER.get(typeRl);
        if (ser == null) {
            Credit.LOGGER.info("[CraftPattern] recover: RecipeSerializer 未登録 type={} id={}", typeRl, r.recipeId());
            return Optional.empty();
        }

        try {
            ResourceLocation recipeRl;
            try { recipeRl = ResourceLocation.parse(r.recipeId()); }
            catch (Exception e) {
                Credit.LOGGER.info("[CraftPattern] recover: recipeId 不正 {}: {}", r.recipeId(), e.getMessage());
                return Optional.empty();
            }
            // 1.21: RecipeSerializer.fromJson 廃止 → codec().codec().parse() で復元 (registry 参照に RegistryOps)
            Recipe<?> recipe = ser.codec().codec().parse(jsonOps(), json).result().orElse(null);
            if (recipe == null) {
                Credit.LOGGER.info("[CraftPattern] recover: ser.fromJson が null 返却 type={} id={}", typeRl, r.recipeId());
            }
            return Optional.ofNullable(recipe);
        } catch (Exception e) {
            Credit.LOGGER.info("[CraftPattern] recover: Recipe.fromJson 例外 type={} id={}: {}",
                typeRl, r.recipeId(), e.getMessage());
            return Optional.empty();
        }
    }

    // ─── v3.12-B: mod recipe 復元 (= id lookup のみ。 ADD は null) ───

    @Nullable
    private static Recipe<?> recoverModRecipe(ImportedRecipe r) {
        if (r.kind() == OperationKind.EDIT && r.origRecipeId() != null) {
            return lookupExistingRecipe(r.origRecipeId());
        }
        // ADD は credit:generated/... なので RecipeManager に存在しない → null → Screen 側 placeholder
        return null;
    }

    @Nullable
    private static Recipe<?> lookupExistingRecipe(String id) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.level == null) return null;
            ResourceLocation rl = ResourceLocation.parse(id);
            // 1.21: RecipeManager.byKey は Optional<RecipeHolder<?>> → .value() で unwrap
            return mc.level.getRecipeManager().byKey(rl).map(net.minecraft.world.item.crafting.RecipeHolder::value).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── v3.4-3: vanilla cooking + stonecutting 復元 ───

    private static final Pattern XP_PATTERN = Pattern.compile("\\.xp\\(\\s*([0-9]+\\.?[0-9]*)\\s*\\)");
    private static final Pattern COOKINGTIME_PATTERN = Pattern.compile("\\.cookingTime\\(\\s*(\\d+)\\s*\\)");

    @Nullable
    private static Recipe<?> buildVanillaCookingRecipe(ImportedRecipe r) {
        String method = r.recipeType();
        String args = RecipeReEmitter.extractCallArgs(r.codeBody(), "event." + method);
        if (args == null) return null;
        List<String> topArgs = RecipeReEmitter.splitTopLevelArgs(args);
        if (topArgs.size() < 2) return null;
        IngredientSpec outSpec = RecipeReEmitter.parseItemSpec(topArgs.get(0).trim());
        IngredientSpec inSpec  = RecipeReEmitter.parseItemSpec(topArgs.get(1).trim());
        if (outSpec == null || outSpec.isEmpty() || inSpec == null || inSpec.isEmpty()) return null;
        ItemStack outStack = RecipeDraft.toOutputStack(outSpec);
        Ingredient ing = RecipeDraft.toIngredient(inSpec);
        if (outStack.isEmpty()) return null;

        float xp = 0f;
        int cookingTime = defaultCookingTimeFor(method);
        boolean xpFound = false, ctFound = false;
        Matcher xm = XP_PATTERN.matcher(r.codeBody());
        if (xm.find()) {
            try { xp = Float.parseFloat(xm.group(1)); xpFound = true; } catch (Exception ignored) {}
        }
        Matcher cm = COOKINGTIME_PATTERN.matcher(r.codeBody());
        if (cm.find()) {
            try { cookingTime = Integer.parseInt(cm.group(1)); ctFound = true; } catch (Exception ignored) {}
        }
        Credit.LOGGER.info("[CraftPattern] recover ({}): id={}, kind={}, xpFound={} (xp={}), ctFound={} (cookingTime={}), out={}, in={}",
            method, r.recipeId(), r.kind(), xpFound, xp, ctFound, cookingTime,
            outStack.getDescriptionId(), ing.getItems().length > 0 ? ing.getItems()[0].getDescriptionId() : "?");

        ResourceLocation id = parseRecipeRl(r.recipeId());
        if (id == null) return null;
        CookingBookCategory cat = defaultCookingCategoryFor(method);
        return switch (method) {
            case "smelting"        -> new SmeltingRecipe("", cat, ing, outStack, xp, cookingTime);
            case "blasting"        -> new BlastingRecipe("", cat, ing, outStack, xp, cookingTime);
            case "smoking"         -> new SmokingRecipe("", cat, ing, outStack, xp, cookingTime);
            case "campfireCooking" -> new CampfireCookingRecipe("", cat, ing, outStack, xp, cookingTime);
            default -> null;
        };
    }

    @Nullable
    private static Recipe<?> buildVanillaStonecuttingRecipe(ImportedRecipe r) {
        String args = RecipeReEmitter.extractCallArgs(r.codeBody(), "event.stonecutting");
        if (args == null) return null;
        List<String> topArgs = RecipeReEmitter.splitTopLevelArgs(args);
        if (topArgs.size() < 2) return null;
        IngredientSpec outSpec = RecipeReEmitter.parseItemSpec(topArgs.get(0).trim());
        IngredientSpec inSpec  = RecipeReEmitter.parseItemSpec(topArgs.get(1).trim());
        if (outSpec == null || outSpec.isEmpty() || inSpec == null || inSpec.isEmpty()) return null;
        ItemStack outStack = RecipeDraft.toOutputStack(outSpec);
        Ingredient ing = RecipeDraft.toIngredient(inSpec);
        if (outStack.isEmpty()) return null;
        ResourceLocation id = parseRecipeRl(r.recipeId());
        if (id == null) return null;
        return new StonecutterRecipe("", ing, outStack);
    }

    private static int defaultCookingTimeFor(String method) {
        return switch (method) {
            case "smelting"        -> 200;
            case "blasting"        -> 100;
            case "smoking"         -> 100;
            case "campfireCooking" -> 600;
            default                -> 200;
        };
    }

    private static CookingBookCategory defaultCookingCategoryFor(String method) {
        return switch (method) {
            case "smoking", "campfireCooking" -> CookingBookCategory.FOOD;
            default                            -> CookingBookCategory.MISC;
        };
    }

    /** world 接続中なら RegistryOps (registry 参照可)、未接続なら素の JsonOps。 */
    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> jsonOps() {
        try {
            var conn = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (conn != null) return net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, conn.registryAccess());
        } catch (Throwable ignored) {}
        return com.mojang.serialization.JsonOps.INSTANCE;
    }

    @Nullable
    private static ResourceLocation parseRecipeRl(String s) {
        try { return ResourceLocation.parse(s); } catch (Exception e) { return null; }
    }

    /** event.custom(...) の (...) 内 JSON object を切り出す。括弧バランス追跡。失敗時 null。 */
    @Nullable
    private static String extractCustomJsonObject(String code) {
        Matcher m = CUSTOM_OPEN.matcher(code);
        if (!m.find()) return null;
        int openIdx = m.end() - 1;
        int close = findMatching(code, openIdx, '(', ')');
        if (close < 0) return null;
        String inside = code.substring(openIdx + 1, close).trim();
        int braceStart = inside.indexOf('{');
        if (braceStart < 0) return null;
        int braceEnd = findMatching(inside, braceStart, '{', '}');
        if (braceEnd < 0) return null;
        return inside.substring(braceStart, braceEnd + 1);
    }

    /** 開き括弧位置から対応閉じ括弧位置を探す (文字列/コメント無視)。失敗時 -1。 */
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
}
