package DIV.credit.client.draft.gt;

import DIV.credit.Credit;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase-gt-conflict (B 案): GT recipe の input 衝突を自前判定。 push 前に chat WARN を出すための pre-check。
 *
 * <p>GT mod の {@code GTRecipeLookup.findRecipeCollisions} を直接呼ぶには
 * {@code IRecipeCapabilityHolder} の dummy 実装か reflection が要るため、 内部依存を避け
 * 自前で input fingerprint 比較する。 GT の厳密な ingredient match と異なるが
 * (例: tag/item の細かい差は捉えない場合あり)、 大半のケースで意図せぬ衝突を検出できる。
 *
 * <p>使用箇所: {@link DIV.credit.client.screen.BuilderScreen} の dump 直前。
 * 衝突検出時は chat WARN を出すだけで dump 自体は止めない (= ユーザー判断尊重)。
 */
public final class GTConflictChecker {

    private GTConflictChecker() {}

    /** newRecipe と同 RecipeType + 同 input fingerprint の既存 recipe を最大 N 件返す。 */
    public static List<GTRecipe> findConflicts(GTRecipe newRecipe, int limit) {
        if (newRecipe == null) return List.of();
        GTRecipeType type = newRecipe.recipeType;
        if (type == null) return List.of();
        var mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return List.of();

        String newFp = inputFingerprint(newRecipe);
        if (newFp.isEmpty()) return List.of();  // 入力なし → 衝突判定不能

        List<GTRecipe> conflicts = new ArrayList<>();
        var rm = mc.level.getRecipeManager();
        // RecipeManager.byType は 1.20.1 で package-private → getRecipes() で全 iterate + type filter
        for (Recipe<?> r : rm.getRecipes()) {
            if (r.getType() != type) continue;
            if (!(r instanceof GTRecipe other)) continue;
            // 同一 recipe (= id 一致) はスキップ
            if (newRecipe.getId() != null && newRecipe.getId().equals(other.getId())) continue;
            if (newFp.equals(inputFingerprint(other))) {
                conflicts.add(other);
                if (conflicts.size() >= limit) break;
            }
        }
        return conflicts;
    }

    /** input の安定 fingerprint 文字列化。 RecipeCapability → Content list (sorted) を join。 */
    private static String inputFingerprint(GTRecipe r) {
        if (r.inputs == null || r.inputs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        List<RecipeCapability<?>> caps = new ArrayList<>(r.inputs.keySet());
        caps.sort(Comparator.comparing(c -> c.getClass().getName()));
        for (RecipeCapability<?> cap : caps) {
            sb.append(cap.getClass().getSimpleName()).append('=');
            List<Content> list = r.inputs.get(cap);
            if (list == null || list.isEmpty()) { sb.append(";"); continue; }
            List<String> contents = new ArrayList<>(list.size());
            for (Content c : list) {
                contents.add(c == null || c.content == null ? "null" : c.content.toString());
            }
            contents.sort(null);
            sb.append(contents);
            sb.append(';');
        }
        return sb.toString();
    }
}
