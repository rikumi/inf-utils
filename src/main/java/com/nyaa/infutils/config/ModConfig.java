package com.nyaa.infutils.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

import java.util.ArrayList;
import java.util.List;

@Config(name = "nyaa-infinite-infernal-utils")
public class ModConfig implements ConfigData {

    @Comment("启用或禁用整个模组")
    public boolean modEnabled = true;

    @Comment("调试模式，启用额外日志")
    public boolean debugMode = false;

    // ==================== Feature: Summon Glow ====================

    @ConfigEntry.Gui.CollapsibleObject
    public SummonGlowSettings summonGlow = new SummonGlowSettings();

    public static class SummonGlowSettings {
        @Comment("将召唤物（隐形盔甲架）显示为发光")
        public boolean enabled = true;

        public enum SummonGlowMode {
            /** 原版轮廓光 */
            OUTLINE,
            /** 自身亮度：渲染实体本身更亮而非轮廓 */
            BRIGHTNESS
        }

        @Comment("发光模式：OUTLINE=原版轮廓光；BRIGHTNESS=强制全亮（光影下效果明显）")
        public SummonGlowMode glowMode = SummonGlowMode.BRIGHTNESS;

        @ConfigEntry.ColorPicker
        @Comment("轮廓光颜色（RRGGBB），仅OUTLINE模式使用")
        public int glowColor = 0x00FFFF;

        @Comment("仅将有装备的隐形盔甲架视为召唤物")
        public boolean requireEquipment = true;
    }

    // ==================== Feature: Monster (怪物) Glow ====================

    @ConfigEntry.Gui.CollapsibleObject
    public MonsterGlowSettings monsterGlow = new MonsterGlowSettings();

    public static class MonsterGlowSettings {
        @Comment("使所有怪物发光")
        public boolean enabled = false;

        public enum MonsterGlowMode {
            /** 原版轮廓光 */
            OUTLINE,
            /** 自身亮度：渲染实体本身更亮而非轮廓 */
            BRIGHTNESS
        }

        @Comment("发光模式：OUTLINE=原版轮廓光；BRIGHTNESS=强制全亮（光影下效果明显）")
        public MonsterGlowMode glowMode = MonsterGlowMode.BRIGHTNESS;

        @ConfigEntry.ColorPicker
        @Comment("轮廓光颜色，仅OUTLINE模式使用")
        public int glowColor = 0xFFFFFF;
    }

    // ==================== Feature: Elite (精英怪) Glow ====================

    @ConfigEntry.Gui.CollapsibleObject
    public EliteGlowSettings eliteGlow = new EliteGlowSettings();

    public static class EliteGlowSettings {
        @Comment("使所有精英怪发光")
        public boolean enabled = true;

        @ConfigEntry.ColorPicker
        @Comment("轮廓光颜色")
        public int glowColor = 0xFF0000;
    }

    // ==================== Feature: Attack Curve ====================

    @ConfigEntry.Gui.CollapsibleObject
    public AttackCurveSettings attackCurve = new AttackCurveSettings();

    public static class AttackCurveSettings {
        @Comment("将服务器连续粒子渲染为泰拉瑞亚式发光攻击曲线")
        public boolean enabled = true;

        @Comment("两粒子被视为同曲线的最大距离（格），保持较低避免散乱粒子串联，攻击弧断裂时可调高")
        public float maxSegmentDistance = 0.3F;

        @Comment("每帧追踪的最大服务器粒子数")
        @ConfigEntry.BoundedDiscrete(min = 16, max = 4096)
        public int maxTrackedParticles = 1024;

        @Comment("粒子位置保留时间（tick）")
        @ConfigEntry.BoundedDiscrete(min = 1, max = 60)
        public int particleLifetimeTicks = 4;

        @Comment("使用粒子自身颜色；关闭则使用下方固定颜色")
        public boolean useParticleColor = true;

        @ConfigEntry.ColorPicker
        @Comment("固定曲线颜色（RRGGBB），关闭使用粒子自身颜色时使用")
        public int curveColor = 0xFFAA33;

        @Comment("发光带宽度（格），中间粗两端细")
        @ConfigEntry.BoundedDiscrete(min = 1, max = 32)
        public float lineWidth = 1.0F;

        @Comment("核心透明度（0-1），1.0=最亮，外层光晕取此值45%")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 1)
        public float opacity = 0.7F;

        @Comment("带宽随粒子间距缩放系数，1=恒定宽度，>1可让稀疏粒子仍呈连续带")
        public float scaleBandWidth = 1.0F;

        @Comment("最小连接粒子数，低于此值视为孤立光点")
        @ConfigEntry.BoundedDiscrete(min = 2, max = 16)
        public int minCurvePoints = 2;

        @Comment("叠加发光/泛光效果")
        public boolean additiveGlow = true;

        @Comment("显示屏幕调试信息（粒子/点/曲线数）")
        public boolean showDebug = false;

        @Comment("隐藏原粒子仅渲染曲线；false时曲线叠加在原粒子上方")
        public boolean replaceParticleRendering = true;

        @Comment("仅将服务器发送的粒子转为曲线；原版本地粒子不受影响")
        public boolean attackOnly = true;

        @Comment("仅转换类型ID包含这些字符串的粒子（空=全部），逗号分隔")
        public List<String> particleTypes = new ArrayList<>();

        @Comment("不转换类型ID包含这些字符串的粒子，逗号分隔")
        public List<String> excludeParticleTypes = new ArrayList<>(List.of("end_gateway"));
    }

    // ==================== Feature: Region Overlay ====================

    @ConfigEntry.Gui.CollapsibleObject
    public RegionOverlaySettings regionOverlay = new RegionOverlaySettings();

    public static class RegionOverlaySettings {
        @Comment("记住最后显示的区域名并持久显示在 bossbar 下方居中")
        public boolean enabled = true;

        @Comment("水平偏移（像素），相对于屏幕中心")
        public int xOffset = 0;

        @Comment("垂直位置（像素），从屏幕顶部起（默认 36 ≈ bossbar 下方）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 4000)
        public int yOffset = 36;

        @Comment("文字颜色")
        @ConfigEntry.ColorPicker
        public int textColor = 0xFFFFFF;

        @Comment("文字不透明度（0-255）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 255)
        public int textAlpha = 230;

        @Comment("文字阴影")
        public boolean shadow = true;

        @Comment("记录每次区域变化（调试用）")
        public boolean debugLog = false;
    }

    // ==================== Feature: Sound Replace (Terraria wand SFX) ====================

    @ConfigEntry.Gui.CollapsibleObject
    public SoundReplaceSettings soundReplace = new SoundReplaceSettings();

    public static class SoundReplaceSettings {
        @Comment("将服务器攻击音效替换为泰拉瑞亚式法杖音效")
        public boolean enabled = true;

        @Comment("仅替换ID包含这些子串的音效（空=使用内置过滤，推荐），逗号分隔")
        public List<String> matchFilters = new ArrayList<>();

        @Comment("不替换这些类别的音效（子串，小写），默认保护音乐/唱片/天气/环境/语音")
        public List<String> excludeCategories = new ArrayList<>(List.of("music", "records", "ambient", "weather", "voice"));

        @Comment("音效ID子串→替换音效映射，格式:'子串=magic_item9'，空=自动映射")
        public List<String> mapping = new ArrayList<>();

        @Comment("随机选择映射音效而非固定音效")
        public boolean randomize = true;

        @Comment("替换音效音量倍率（1.0=原音量）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 5)
        public float volume = 2.0F;

        @Comment("替换音效音调倍率（1.0=原音调）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 2)
        public float pitch = 1.0F;

        @Comment("记录每次音效替换（调试用）")
        public boolean verbose = false;
    }

    // ==================== Feature: Auto Use ====================

    @ConfigEntry.Gui.CollapsibleObject
    public AutoUseSettings autoUse = new AutoUseSettings();

    public static class AutoUseSettings {
        @Comment("自动使用系统总开关")
        public boolean enabled = true;

        @Comment("物品使用的快捷栏槽位（1-9），默认5")
        @ConfigEntry.BoundedDiscrete(min = 1, max = 9)
        public int slot = 5;

        @Comment("两次自动使用间隔（tick），20tick=1秒")
        @ConfigEntry.BoundedDiscrete(min = 1, max = 120)
        public int cooldownTicks = 4;

        @ConfigEntry.Gui.CollapsibleObject
        public HealthPotionSettings healthPotion = new HealthPotionSettings();

        public static class HealthPotionSettings {
            @Comment("生命值缺失达到阈值时自动右键使用生命药剂，默认开启")
            public boolean enabled = true;

            @Comment("触发使用药剂的缺失生命值（半心）")
            @ConfigEntry.BoundedDiscrete(min = 1, max = 200)
            public int threshold = 16;

            @Comment("优先使用最大药剂")
            public boolean preferLargestPotion = false;
        }

        @ConfigEntry.Gui.CollapsibleObject
        public ManaPotionSettings manaPotion = new ManaPotionSettings();

        public static class ManaPotionSettings {
            @Comment("魔力低于阈值时自动右键使用魔力药剂，默认关闭")
            public boolean enabled = false;

            @Comment("触发使用药剂的魔力值")
            @ConfigEntry.BoundedDiscrete(min = 1, max = 1000)
            public int threshold = 15;

            @Comment("优先使用最大药剂")
            public boolean preferLargestPotion = false;
        }

        @ConfigEntry.Gui.CollapsibleObject
        public PiggyBankSettings piggyBank = new PiggyBankSettings();

        public static class PiggyBankSettings {
            @Comment("出现一文大钱时自动左键存钱罐，默认关闭")
            public boolean enabled = false;

            @Comment("背包中一文大钱数量达到此值时触发自动存入")
            @ConfigEntry.BoundedDiscrete(min = 1, max = 2304)
            public int threshold = 64;

            @Comment("两次存钱罐使用间隔（tick）")
            @ConfigEntry.BoundedDiscrete(min = 1, max = 200)
            public int intervalTicks = 20;
        }

        @Comment("残魂收集刷耐久满且冷却为0时自动左键使用，默认关闭")
        public boolean soulBrush = false;

        @ConfigEntry.Gui.CollapsibleObject
        public ChargeRepairSettings chargeRepair = new ChargeRepairSettings();

        public static class ChargeRepairSettings {
            @Comment("耐久≤1且有消耗物品时自动潜行+左键充能，默认关闭")
            public boolean chargeEnabled = false;

            @Comment("耐久≤半且有消耗物品时自动潜行+左键修复，默认关闭")
            public boolean repairEnabled = false;
        }

        @ConfigEntry.Gui.CollapsibleObject
        public ArmorRepairSettings armoraRepair = new ArmorRepairSettings();

        public static class ArmorRepairSettings {
            @Comment("自动脱下护甲修复后重新穿上，默认关闭")
            public boolean repairEnabled = false;
        }

        @Comment("能量剩余为1时自动充能动力背包，默认关闭")
        public boolean backpackCharge = false;

        @ConfigEntry.Gui.CollapsibleObject
        public FoodSettings food = new FoodSettings();

        public static class FoodSettings {
            @Comment("饥饿缺失超过阈值时自动右键使用食物，仅食用白名单RPG食物和无lore原版食物，默认开启")
            public boolean enabled = true;

            @Comment("触发进食的缺失饥饿值（半肉排）")
            @ConfigEntry.BoundedDiscrete(min = 1, max = 20)
            public int threshold = 8;

            @Comment("手持名称包含此列表物品时不自动进食（忽略颜色代码）")
            public List<String> blacklist = new ArrayList<>(List.of("发明家动力背包"));
        }

        @ConfigEntry.Gui.CollapsibleObject
        public AutoSpawnSettings autoSpawn = new AutoSpawnSettings();

        public static class AutoSpawnSettings {
            @Comment("生命值低于阈值且无生命药剂时自动执行/spawn逃生，默认关闭")
            public boolean enabled = false;

            @Comment("触发/spawn的生命值（半心）")
            @ConfigEntry.BoundedDiscrete(min = 1, max = 200)
            public int threshold = 16;

            @Comment("自动/spawn重试间隔（tick），20tick=1秒")
            @ConfigEntry.BoundedDiscrete(min = 5, max = 1200)
            public int retryTicks = 20;
        }

        @ConfigEntry.Gui.CollapsibleObject
        public SummonResummonSettings summonResummon = new SummonResummonSettings();

        public static class SummonResummonSettings {
            @Comment("自动重新召唤消失的召唤物，默认关闭")
            public boolean enabled = false;

            @Comment("召唤物寿命（秒），到期后自动重放")
            @ConfigEntry.BoundedDiscrete(min = 30, max = 600)
            public int lifetimeSeconds = 120;

            @Comment("绑定最近右键物品的召唤物扫描半径（格）")
            @ConfigEntry.BoundedDiscrete(min = 4, max = 64)
            public int radius = 16;

            @Comment("同一武器两次重召最小间隔（tick），60tick=3秒")
            @ConfigEntry.BoundedDiscrete(min = 5, max = 1200)
            public int resummonCooldownTicks = 60;
        }
    }
}
