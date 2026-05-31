package DIV.credit.client.devtest;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import DIV.credit.client.draft.mek.MekanismKubeJSEmitter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.fluids.FluidStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 起動時 (client setup) に、 ワールドに入らず emit コードを自動生成してファイル出力する dev テストハーネス。
 *
 * <p>目的: 「ワールド→編集→commit→push→/reload」の手動サイクル無しで、 recipeType + ingredient名 を与えると
 * <b>現行の emit 経路そのまま</b>で生成コードが {@code run/credit_emittest/emit_test.txt} に出る。
 *
 * <p>ケース定義:
 * <ul>
 *   <li>{@code run/credit_emittest/cases.txt} があればそれを読む (= 再コンパイル不要で編集可能)。</li>
 *   <li>無ければ既定ケースを実行し、 サンプル {@code cases.txt} を書き出す。</li>
 * </ul>
 * cases.txt 1 行 = {@code recipeType ; KIND name amount ; KIND name amount ; ...}
 *  (KIND = ITEM_INPUT/ITEM_OUTPUT/FLUID_INPUT/FLUID_OUTPUT/GAS_INPUT/GAS_OUTPUT。 name の先頭 # は tag。)
 *  recipeType に {@code credit:itemstring} を指定すると、 各 slot の item→KubeJS文字列翻訳(#2)を出力。
 */
@EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class EmitTestHarness {

    private EmitTestHarness() {}

    private static final Path DIR        = Paths.get("credit_emittest");
    private static final Path OUT_FILE   = DIR.resolve("emit_test.txt");
    private static final Path CASES_FILE = DIR.resolve("cases.txt");

    public record SlotInput(SlotKind kind, String name, int amount) {}
    public record TestCase(String recipeType, List<SlotInput> slots) {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // registry は client setup 時点で frozen 済 (item/fluid/chemical id 解決可)。 world 不要。
        event.enqueueWork(EmitTestHarness::run);
    }

    private static void run() {
        try {
            Files.createDirectories(DIR);
            List<TestCase> cases = Files.exists(CASES_FILE) ? readCases() : null;
            boolean usedDefaults = false;
            if (cases == null || cases.isEmpty()) {
                cases = defaultCases();
                usedDefaults = true;
                writeSampleCases(cases);
            }

            StringBuilder out = new StringBuilder();
            out.append("=== credit Emit Test (no-world, client-setup) ===\n");
            out.append("source: ").append(usedDefaults ? "default cases (edit cases.txt to override)" : CASES_FILE).append("\n");
            out.append("cases: ").append(cases.size()).append("\n\n");
            for (TestCase tc : cases) emitOne(out, tc);

            Files.writeString(OUT_FILE, out.toString(), StandardCharsets.UTF_8);
            Credit.LOGGER.info("[CraftPattern][EmitTest] done: {} cases -> {}", cases.size(), OUT_FILE.toAbsolutePath());
        } catch (Throwable t) {
            Credit.LOGGER.error("[CraftPattern][EmitTest] harness failed", t);
        }
    }

    private static void emitOne(StringBuilder out, TestCase tc) {
        out.append("--- ").append(tc.recipeType()).append(" ---\n");
        for (SlotInput s : tc.slots()) {
            out.append("    in: ").append(s.kind()).append(' ').append(s.name()).append(" x").append(s.amount()).append('\n');
        }
        try {
            String code = generate(tc.recipeType(), tc.slots());
            out.append(code == null ? "    >> (null / skeleton — 未対応 schema)\n" : indent(code));
        } catch (Throwable t) {
            out.append("    >> ERROR: ").append(t).append('\n');
        }
        out.append('\n');
    }

    /** recipeType + slot 群 → 現行 emit 経路で KubeJS コード文字列。 未対応なら null。 */
    public static String generate(String recipeTypeUid, List<SlotInput> inputs) {
        ResourceLocation uid = ResourceLocation.parse(recipeTypeUid);
        String recipeId = "credit:generated/test/" + uid.getPath();

        // #2 検証用: item→KubeJS文字列翻訳 (DataComponents) を直接出す擬似タイプ
        if ("credit".equals(uid.getNamespace()) && "itemstring".equals(uid.getPath())) {
            StringBuilder sb = new StringBuilder();
            for (SlotInput in : inputs) {
                IngredientSpec spec = specFor(in);
                if (spec instanceof IngredientSpec.Item it) {
                    sb.append("    ").append(RecipeDraft.formatItemString(it.stack())).append('\n');
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        IngredientSpec[] specs = new IngredientSpec[inputs.size()];
        SlotKind[] kinds = new SlotKind[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            kinds[i] = inputs.get(i).kind();
            specs[i] = specFor(inputs.get(i));
        }

        // Mekanism: 現行 emit 経路 (GenericDraft.emit が呼ぶのと同じ MekanismKubeJSEmitter)
        if ("mekanism".equals(uid.getNamespace()) || "evolvedmekanism".equals(uid.getNamespace())) {
            return MekanismKubeJSEmitter.emit(recipeId, uid, specs, kinds);
        }
        return "(harness: " + uid + " の直接 emitter 未実装。 GenericDraft auto-pipeline 経路は JEI runtime が要るため world テストで確認)";
    }

    /** SlotInput → IngredientSpec。 name 先頭 # は tag。 */
    private static IngredientSpec specFor(SlotInput in) {
        String name = in.name();
        int amt = Math.max(1, in.amount());
        boolean isItemKind = in.kind() == SlotKind.ITEM_INPUT || in.kind() == SlotKind.ITEM_OUTPUT;
        boolean isFluidKind = in.kind() == SlotKind.FLUID_INPUT || in.kind() == SlotKind.FLUID_OUTPUT;
        if (name.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.parse(name.substring(1));
            return isFluidKind ? IngredientSpec.ofFluidTag(tag, amt) : IngredientSpec.ofTag(tag);
        }
        ResourceLocation rl = ResourceLocation.parse(name);
        if (in.kind() == SlotKind.GAS_INPUT || in.kind() == SlotKind.GAS_OUTPUT) {
            return IngredientSpec.ofGas(rl, amt);
        }
        if (isFluidKind) {
            Fluid f = BuiltInRegistries.FLUID.get(rl);
            return IngredientSpec.ofFluid(new FluidStack(f, amt));
        }
        var item = BuiltInRegistries.ITEM.get(rl);
        return IngredientSpec.ofItem(new ItemStack(item, Math.min(amt, 64)));
    }

    private static List<TestCase> defaultCases() {
        List<TestCase> c = new ArrayList<>();
        // chemical_infusing: 2 chemical -> chemical
        c.add(new TestCase("mekanism:chemical_infusing", List.of(
            new SlotInput(SlotKind.GAS_INPUT,  "mekanism:hydrogen", 1),
            new SlotInput(SlotKind.GAS_INPUT,  "mekanism:chlorine", 1),
            new SlotInput(SlotKind.GAS_OUTPUT, "mekanism:hydrogen_chloride", 1))));
        // oxidizing: item -> chemical
        c.add(new TestCase("mekanism:oxidizing", List.of(
            new SlotInput(SlotKind.ITEM_INPUT,  "minecraft:gunpowder", 1),
            new SlotInput(SlotKind.GAS_OUTPUT,  "mekanism:hydrogen", 100))));
        // purifying: item + chemical -> item
        c.add(new TestCase("mekanism:purifying", List.of(
            new SlotInput(SlotKind.ITEM_INPUT,  "minecraft:iron_ore", 1),
            new SlotInput(SlotKind.GAS_INPUT,   "mekanism:oxygen", 1),
            new SlotInput(SlotKind.ITEM_OUTPUT, "mekanism:clump_iron", 2))));
        // crushing: item -> item
        c.add(new TestCase("mekanism:crushing", List.of(
            new SlotInput(SlotKind.ITEM_INPUT,  "minecraft:cobblestone", 1),
            new SlotInput(SlotKind.ITEM_OUTPUT, "minecraft:gravel", 1))));
        // #2: item -> KubeJS文字列 (component 無しの素材確認。 component付きは world で検証済)
        c.add(new TestCase("credit:itemstring", List.of(
            new SlotInput(SlotKind.ITEM_OUTPUT, "minecraft:diamond", 1),
            new SlotInput(SlotKind.ITEM_OUTPUT, "minecraft:diamond_sword", 1))));
        return c;
    }

    private static List<TestCase> readCases() {
        List<TestCase> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(CASES_FILE, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue;
                String[] parts = s.split(";");
                if (parts.length < 2) continue;
                String type = parts[0].trim();
                List<SlotInput> slots = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    String[] t = parts[i].trim().split("\\s+");
                    if (t.length < 2) continue;
                    SlotKind kind = SlotKind.valueOf(t[0].trim().toUpperCase());
                    int amt = t.length >= 3 ? parseIntSafe(t[2]) : 1;
                    slots.add(new SlotInput(kind, t[1].trim(), amt));
                }
                if (!slots.isEmpty()) out.add(new TestCase(type, slots));
            }
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern][EmitTest] cases.txt parse failed: {}", e.toString());
        }
        return out;
    }

    private static void writeSampleCases(List<TestCase> cases) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# credit emit test cases (起動時に自動実行 → emit_test.txt)\n");
            sb.append("# 形式: recipeType ; KIND name amount ; KIND name amount ; ...\n");
            sb.append("# KIND: ITEM_INPUT/ITEM_OUTPUT/FLUID_INPUT/FLUID_OUTPUT/GAS_INPUT/GAS_OUTPUT  (name 先頭#=tag)\n");
            sb.append("# 例:\n");
            for (TestCase tc : cases) {
                sb.append(tc.recipeType());
                for (SlotInput s : tc.slots()) {
                    sb.append(" ; ").append(s.kind()).append(' ').append(s.name()).append(' ').append(s.amount());
                }
                sb.append('\n');
            }
            if (!Files.exists(CASES_FILE)) Files.writeString(CASES_FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 1; }
    }

    private static String indent(String code) {
        StringBuilder sb = new StringBuilder();
        for (String l : code.split("\n", -1)) sb.append("    >> ").append(l).append('\n');
        return sb.toString();
    }
}
