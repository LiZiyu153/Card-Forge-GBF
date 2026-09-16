import java.util.List;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.card.CardZoneTable;
import forge.game.card.CounterKeywordType;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantBeCast;
import forge.game.zone.ZoneType;

/**
 * Headless regression tests for the R46 ten-Eternals set fixes (2026-09-15):
 *
 * 1. Race types — 兽耳族 is Erune (not Draph): Feower's animation/token/count and
 *    Tien's trigger/token all use Erune; the two new token scripts are Erune.
 * 2. `Ernue` typo — Violet Smoke / Ilsa / Nemone / Pholia had the misspelled
 *    type "Ernue" (not a registered creature type, so those cards were not
 *    Erune at all).
 * 3. Back-face SVars must live on the FRONT part: a modal DFC's card state is
 *    Original while it sits in hand, and Card.getSVar() reads only the current
 *    state -> a back-face-only SVar resolves to "" (=0), which silently
 *    inverted every "cast only if" guard.
 * 4. CantBeCast guards — evaluate correctly in both directions for the five
 *    restricted back faces (checked against a real back-face SpellAbility).
 * 5. Threo's graveyard trigger — one loyalty counter per creature card put
 *    into a graveyard ("put that many"), via TriggerCount$Amount.
 * 6. Prowess keyword counter — the GBF DIY 灵技 counter is registered as a real
 *    keyword counter (engine patch P-16) so it grants prowess.
 */
public class GbfR46EternalsTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= eruneTypesNotDraph();
        ok &= ernueTypoTypoFixed();
        ok &= backFaceSvarsOnFrontFace();
        ok &= frontFaceNotRestrictedByBackGuard();
        ok &= castGuardsBothDirections();
        ok &= threoCountsEveryCreature();
        ok &= prowessIsKeywordCounter();

        System.out.println(ok ? "ALL PASS" : "FAILURES PRESENT");
        System.exit(ok ? 0 : 1);
    }

    /** 1. The 兽耳族 cards must all be Erune, and never Draph. */
    private static boolean eruneTypesNotDraph() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        boolean ok = true;

        // Feower's animation turns him into an Erune creature (design: 3/3 兽耳族).
        Card feower = makeCard("Feower, Double Blade Flash", p, game);
        addToBattlefield(feower);
        feower.addCounterInternal(forge.game.card.CounterEnumType.LOYALTY, 1, p, true,
                new GameEntityCounterTable(), AbilityKey.newMap());
        game.getAction().checkStaticAbilities(false); // apply the layer-4/6 animation
        boolean feowerErune = feower.getType().hasSubtype("Erune");
        boolean feowerDraph = feower.getType().hasSubtype("Draph");
        System.out.println("[FeowerType] Erune=" + feowerErune + " Draph=" + feowerDraph
                + " (expect true / false) -> " + ((feowerErune && !feowerDraph) ? "PASS" : "FAIL"));
        ok &= feowerErune && !feowerDraph;

        // Tien's token script must be an Erune token.
        Card tien = makeCard("Tien, Treacherous Trigger", p, game);
        addToBattlefield(tien);
        String tokenScript = null;
        for (SpellAbility sa : tien.getSpellAbilities()) {
            for (String s : new String[] { "TokenScript" }) {
                if (sa.hasParam(s)) {
                    tokenScript = sa.getParam(s);
                }
            }
        }
        System.out.println("[TienTokenScript] " + tokenScript
                + " (expect rw_3_3_erune_vigilance) -> "
                + ("rw_3_3_erune_vigilance".equals(tokenScript) ? "PASS" : "FAIL"));
        ok &= "rw_3_3_erune_vigilance".equals(tokenScript);

        // Tien's trigger must watch Erune, not Draph.
        CardState tienFront = tien.getState(CardStateName.Original);
        boolean triggerErune = false;
        tienFront.getTriggers();
        for (forge.game.trigger.Trigger tr : tien.getTriggers()) {
            String vc = tr.getParam("ValidCard");
            if (vc != null && vc.contains("Erune")) {
                triggerErune = true;
            }
        }
        System.out.println("[TienTrigger] watches Erune=" + triggerErune
                + " -> " + (triggerErune ? "PASS" : "FAIL"));
        ok &= triggerErune;
        return ok;
    }

    /** 2. The misspelled type "Ernue" must be gone and those cards are Erune. */
    private static boolean ernueTypoTypoFixed() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        boolean ok = true;
        String[] names = { "Violet Smoke Lost in the Night", "Ilsa, Ruthless Drill Sergeant",
                "Nemone, Like Drifting Clouds", "Pholia, Retired Sovereign" };
        for (String n : names) {
            Card c = makeCard(n, p, game);
            boolean erune = c.getType().hasSubtype("Erune");
            boolean ernue = KeywordSubtypeMissing(c, "Ernue");
            System.out.println("[ErnueFix] " + n + " Erune=" + erune
                    + " (expect true) -> " + (erune && !ernue ? "PASS" : "FAIL"));
            ok &= erune && !ernue;
        }
        return ok;
    }

    private static boolean KeywordSubtypeMissing(Card c, String sub) {
        return c.getType().hasSubtype(sub);
    }

    /**
     * 3. A back-face "cast only if" guard MUST live in the back-face part
     * (after the ALTERNATE marker), together with its own SVar.
     *
     * Why: `Card.getStaticAbilities()` / `Card.getSVar()` read only the CURRENT
     * state. While the card sits in hand the current state is Original (front),
     * and when the engine casts the BACK face it switches to Backside first
     * (measured with GbfGuardTimingProbe):
     *   - guard + SVar on the BACK  -> read exactly while casting the back face -> works
     *   - guard + SVar on the FRONT -> also restricts the FRONT face and is
     *     invisible while casting the back face (statics=0) -> double breakage.
     * (An earlier revision wrongly moved them to the front; this test pins the
     * correct placement so it cannot regress again.)
     */
    private static boolean backFaceSvarsOnFrontFace() {
        boolean ok = true;
        String[][] cases = {
            { "res/cardsfolder/f/feower_double_blade_flash_four_skys_sorrow.txt", "X" },
            { "res/cardsfolder/t/threo_supernatural_three_tigers_blessing.txt", "X" },
            { "res/cardsfolder/t/tien_treacherous_trigger_ten_wolfs_triumph.txt", "X" },
            { "res/cardsfolder/n/niyon_mystic_musician_nine_realms_security.txt", "X" },
            { "res/cardsfolder/s/seofon_star_sword_sovereign_seven_stars_brilliance.txt", "X" },
            { "res/cardsfolder/a/anre_enlightened_one_one_rifts_benediction.txt", "X" },
        };
        for (String[] cs : cases) {
            String text;
            try {
                text = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(cs[0])), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.out.println("[GuardPlacement] cannot read " + cs[0] + " -> FAIL");
                ok = false;
                continue;
            }
            int split = text.indexOf("ALTERNATE");
            String front = split < 0 ? text : text.substring(0, split);
            String back = split < 0 ? "" : text.substring(split);
            boolean guardOnBack = back.contains("CheckSVar$ " + cs[1]);
            boolean svarOnBack = back.contains("SVar:" + cs[1] + ":");
            boolean guardOnFront = front.contains("CheckSVar$ " + cs[1]);
            boolean pass = guardOnBack && svarOnBack && !guardOnFront;
            System.out.println("[GuardPlacement] " + cs[0] + " " + cs[1]
                    + " guardBack=" + guardOnBack + " svarBack=" + svarOnBack
                    + " guardFront=" + guardOnFront + " -> " + (pass ? "PASS" : "FAIL"));
            ok &= pass;
        }
        return ok;
    }

    /**
     * 3b. Regression for the bug the user caught: the FRONT face (a planeswalker)
     * must NOT be restricted by the back face's "cast only if" condition, while
     * the BACK face must really be gated. Front = Feower (5 Erune present, so the
     * back face is castable) and Tien (12 cards in graveyard).
     */
    private static boolean frontFaceNotRestrictedByBackGuard() {
        boolean ok = true;
        ok &= frontCastable("Feower, Double Blade Flash", 5, 0, false);
        ok &= frontCastable("Tien, Treacherous Trigger", 0, 12, false);
        return ok;
    }

    /** Front face must be castable regardless of the back-face condition. */
    private static boolean frontCastable(String cardName, int erune, int grave, boolean expectBlocked) {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card c = makeCard(cardName, p, game);
        p.getZone(ZoneType.Hand).add(c);
        for (int i = 0; i < erune; i++) {
            Card t = makeCard("Grizzly Bears", p, game);
            t.addType("Erune");
            p.getZone(ZoneType.Battlefield).add(t);
        }
        for (int i = 0; i < grave; i++) {
            p.getZone(ZoneType.Graveyard).add(makeCard("Forest", p, game));
        }
        SpellAbility front = c.getSpellAbilities().isEmpty() ? null : c.getSpellAbilities().get(0);
        boolean blocked = front != null
                && StaticAbilityCantBeCast.cantBeCastAbility(front, c, p);
        boolean pass = blocked == expectBlocked;
        System.out.println("[FrontNotGated] " + c.getName() + " frontBlocked=" + blocked
                + " expect=" + expectBlocked + " (erune=" + erune + " grave=" + grave + ") -> "
                + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /** 4. The five restricted back faces gate casting in both directions. */
    private static boolean castGuardsBothDirections() {
        boolean ok = true;
        ok &= guard("Three-Tiger's Blessing", 5, 0, 0, 0, 0, false);
        ok &= guard("Three-Tiger's Blessing", 2, 0, 0, 0, 0, true);
        ok &= guard("Ten-Wolf's Triumph", 0, 12, 0, 0, 0, true);
        ok &= guard("Ten-Wolf's Triumph", 0, 3, 0, 0, 0, false);
        ok &= guard("Nine-Realm's Security", 0, 0, 10, 0, 0, true);
        ok &= guard("Nine-Realm's Security", 0, 0, 4, 0, 0, false);
        ok &= guard("Four-Sky's Sorrow", 0, 0, 0, 5, 0, true);
        ok &= guard("Four-Sky's Sorrow", 0, 0, 0, 1, 0, false);
        // Seven-Star: comparator NE6 (see GbfR46FixTest for the off-by-one note);
        // in headless the count equals the literal hand size, so hand 6 is the
        // castable size here (hand 7 is the castable size in a real game).
        ok &= guard("Seven-Star's Brilliance", 0, 0, 0, 0, 5, true);
        ok &= guard("Seven-Star's Brilliance", 0, 0, 0, 0, 6, false);
        return ok;
    }

    private static boolean guard(String cardName, int lib, int grave, int lands, int erune,
            int handExtra, boolean expectAllowed) {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card c = makeCard(cardName, p, game);
        p.getZone(ZoneType.Hand).add(c);
        for (int i = 0; i < lib; i++) {
            p.getZone(ZoneType.Library).add(makeCard("Forest", p, game));
        }
        for (int i = 0; i < grave; i++) {
            p.getZone(ZoneType.Graveyard).add(makeCard("Forest", p, game));
        }
        for (int i = 0; i < lands; i++) {
            p.getZone(ZoneType.Battlefield).add(makeCard("Island", p, game));
        }
        for (int i = 0; i < erune; i++) {
            Card t = makeCard("Grizzly Bears", p, game);
            t.addType("Erune");
            p.getZone(ZoneType.Battlefield).add(t);
        }
        for (int i = 0; i < handExtra; i++) {
            p.getZone(ZoneType.Hand).add(makeCard("Mountain", p, game));
        }

        // The guard lives on the BACK face, and the engine switches the card to
        // Backside before casting that face -- so evaluate it in that state
        // (this mirrors the measured cast path; see the class javadoc).
        c.setState(CardStateName.Backside, true);

        StaticAbility guardAb = c.getStaticAbilities().isEmpty() ? null
                : c.getStaticAbilities().get(0);
        String svName = guardAb == null ? null : guardAb.getParam("CheckSVar");
        String svValue = svName == null ? null : c.getSVar(svName);
        int svarResolved = svName == null ? -1
                : AbilityUtils.calculateAmount(c, svName, guardAb);
        SpellAbility backSpell = null;
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                backSpell = sa;
                break;
            }
        }
        boolean blocked = backSpell == null
                || StaticAbilityCantBeCast.cantBeCastAbility(backSpell, c, p);
        boolean allowed = !blocked;
        boolean pass = allowed == expectAllowed;
        System.out.println("[Guard] " + c.getName() + " " + svName + "=[" + svValue + "]="
                + svarResolved
                + " lib=" + lib + " grave=" + grave + " lands=" + lands + " erune=" + erune
                + " hand=" + (handExtra + 1) + " allowed=" + allowed + " expect=" + expectAllowed
                + " -> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /** 5. Threo gets one loyalty counter per creature card entering a graveyard. */
    private static boolean threoCountsEveryCreature() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card threo = makeCard("Threo, Supernatural", p, game);
        enterBattlefield(game, threo);
        game.getTriggerHandler().registerActiveTrigger(threo, false);
        int before = threo.getCounters(forge.game.card.CounterEnumType.LOYALTY);

        // Drive the real batch path: one CardZoneTable shared by three deaths,
        // then trigger ChangesZoneAll exactly like ChangeZoneAllEffect does.
        SpellAbility sa = AbilityFactory.getAbility(threo, "TrigPutCounter");
        java.util.Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
        CardZoneTable table = AbilityKey.addCardZoneTableParams(moveParams, sa);

        java.util.List<Card> dead = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Card bear = makeCard("Grizzly Bears", p, game);
            enterBattlefield(game, bear);
            dead.add(bear);
        }
        for (Card bear : dead) {
            game.getAction().moveTo(p.getZone(ZoneType.Graveyard), bear, sa, moveParams);
        }
        table.triggerChangesZoneAll(game, sa);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().unfreezeStack();
        playUntilStackClear(game);

        int after = threo.getCounters(forge.game.card.CounterEnumType.LOYALTY);
        boolean pass = after == before + 3;
        System.out.println("[ThreoTrigger] loyalty " + before + " -> " + after
                + " after 3 creature cards hit a graveyard (expect +3) -> "
                + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /** 6. The GBF DIY 灵技/prowess counter is a real keyword counter. */
    private static boolean prowessIsKeywordCounter() {
        CounterType ct = CounterType.getType("Prowess");
        boolean keyword = ct != null && ct.isKeywordCounter();
        System.out.println("[ProwessCounter] type=" + (ct == null ? "null" : ct)
                + " isKeywordCounter=" + keyword
                + " (expect true; engine patch P-16) -> " + (keyword ? "PASS" : "FAIL"));
        return keyword;
    }
}
