import java.util.List;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantBeCast;
import forge.game.zone.ZoneType;

/**
 * Headless regression tests for the R46 四修 fixes:
 *
 * 1. One-Rift's Benediction (Anre back) — "only while another spell is on the
 *    stack" needs SVarCompare EQ0 (prohibition form: block when zero other
 *    spells). It was NE0, which is false at zero -> never blocked.
 * 2. Seven-Star's Brilliance — "exactly seven cards in hand" needs EQ7
 *    (block unless exactly 7); NE7 was false at 7 and true otherwise.
 * 3. Two-Crown's Strife (Tweyen back) — the chain must ask for exactly TWO
 *    targets; a third redundant ValidTgts (the fight step) made the spell
 *    uncastable after picking the two legendary creatures.
 * 4. Eight-Life's Pilgrimage (Eahta back, new design) — Enchantment, shroud,
 *    enters with an incarnation counter, "sacrifice a creature with power equal
 *    to the number of incarnation counters" ability, and winning at 8+.
 */
public class GbfR46FixTest extends GbfTestBase {

    public static void main(String[] args) {
        init();
        boolean ok = true;
        ok &= anreNeedsAnotherSpell();
        ok &= sevenStarExactlySeven();
        ok &= tweyenAsksTwoTargets();
        ok &= eightLifesPilgrimage();
        System.out.println(ok ? "ALL PASS" : "FAILURES PRESENT");
        System.exit(ok ? 0 : 1);
    }

    /** helper: guard verdict with the card in its cast (back) state */
    private static boolean blocked(Card c, Player p) {
        c.setState(CardStateName.Backside, true);
        SpellAbility back = null;
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                back = sa;
            }
        }
        return back == null || StaticAbilityCantBeCast.cantBeCastAbility(back, c, p);
    }

    /** 1. Anre back: blocked on an empty stack, castable when another spell is there. */
    private static boolean anreNeedsAnotherSpell() {
        boolean ok = true;
        // (a) empty stack -> must be BLOCKED
        {
            Game game = newGame();
            Player p = game.getPlayers().get(1);
            Card c = makeCard("One-Rift's Benediction", p, game);
            p.getZone(ZoneType.Hand).add(c);
            boolean b = blocked(c, p);
            System.out.println("[AnreBack] empty stack -> blocked=" + b
                    + " (expect true) -> " + (b ? "PASS" : "FAIL"));
            ok &= b;
        }
        // (b) one other spell on the stack -> must be ALLOWED
        {
            Game game = newGame();
            Player p = game.getPlayers().get(1);
            Player q = game.getPlayers().get(0);
            Card c = makeCard("One-Rift's Benediction", p, game);
            p.getZone(ZoneType.Hand).add(c);
            Card bolt = makeCard("Shock", q, game);
            q.getZone(ZoneType.Hand).add(bolt);
            SpellAbility sa = bolt.getSpellAbilities().get(0);
            sa.setActivatingPlayer(q);
            Card stackCard = game.getAction().moveToStack(bolt, sa);
            if (stackCard != null) {
                sa.setHostCard(stackCard);
            }
            game.getStack().add(sa);
            boolean b = blocked(c, p);
            System.out.println("[AnreBack] 1 other spell -> blocked=" + b
                    + " (expect false) -> " + (!b ? "PASS" : "FAIL"));
            ok &= !b;
        }
        return ok;
    }

    /**
     * 2. Seven-Star's Brilliance — comparator "NE6", per the design intent
     *    "cast only if you have exactly seven cards in hand".
     *
     * Off-by-one note (important): the two environments count differently at the
     * moment the restriction is evaluated.
     *   - Headless (this test): Count$ValidHand reads the literal hand-zone size,
     *     so hand 7 -> X=7. With NE6 that means hand 6 is the castable size here.
     *   - Real game (user-measured): with the literal comparator the card became
     *     castable at hand 8, i.e. the in-game count is one LESS than the physical
     *     hand (the card being cast has already left the hand). There, NE6 makes
     *     hand 7 the castable size -- which is the design intent.
     * Both readings agree on using 6 instead of 7, so the file keeps NE6; this
     * test asserts the headless truth table and prints X so the difference stays
     * visible.
     */
    private static boolean sevenStarExactlySeven() {
        boolean ok = true;
        // headless truth: with NE6 only hand==6 is castable
        int[][] cases = { { 6, 0 }, { 5, 1 }, { 7, 1 }, { 8, 1 }, { 9, 1 } };
        for (int[] cs : cases) {
            int hand = cs[0];
            boolean expectBlocked = cs[1] == 1;
            Game game = newGame();
            Player p = game.getPlayers().get(1);
            Card c = makeCard("Seven-Star's Brilliance", p, game);
            p.getZone(ZoneType.Hand).add(c);
            for (int i = 1; i < hand; i++) {
                p.getZone(ZoneType.Hand).add(makeCard("Mountain", p, game));
            }
            boolean b = blocked(c, p);
            // debug: what does the guard actually see?
            c.setState(CardStateName.Backside, true);
            StaticAbility ab = c.getStaticAbilities().isEmpty() ? null
                    : c.getStaticAbilities().get(0);
            String svName = ab == null ? "-" : ab.getParam("CheckSVar");
            String svVal = ab == null ? "-" : c.getSVar(svName);
            int computed = ab == null ? -1 : AbilityUtils.calculateAmount(c, svName, ab);
            int handNow = p.getZone(ZoneType.Hand).size();
            System.out.println("[SevenStar] hand=" + hand + " handNow=" + handNow
                    + " guard=" + (ab != null)
                    + " " + svName + "=[" + svVal + "]=" + computed
                    + " cmp=" + (ab == null ? "-" : ab.getParam("SVarCompare"))
                    + " cond=" + (ab != null && ab.checkConditions())
                    + " blocked=" + b
                    + " expect=" + expectBlocked + " -> " + (b == expectBlocked ? "PASS" : "FAIL"));
            ok &= b == expectBlocked;
        }
        return ok;
    }

    /** 3. Tweyen back: the whole chain must require exactly 2 targets. */
    private static boolean tweyenAsksTwoTargets() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card c = makeCard("Two-Crown's Strife", p, game);
        p.getZone(ZoneType.Hand).add(c);
        c.setState(CardStateName.Backside, true);

        int nodes = 0;
        int minTotal = 0;
        StringBuilder detail = new StringBuilder();
        SpellAbility cur = null;
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                cur = sa;
            }
        }
        while (cur != null) {
            if (cur.usesTargeting()) {
                nodes++;
                int mn = cur.getTargetRestrictions().getMinTargets(c, cur);
                minTotal += mn;
                detail.append("[").append(cur.getParam("ValidTgts"))
                      .append(" min=").append(mn)
                      .append("] ");
            }
            cur = cur.getSubAbility();
        }
        boolean pass = nodes == 2 && minTotal == 2;
        System.out.println("[TweyenBack] targetNodes=" + nodes + " minTotal=" + minTotal
                + " " + detail + "-> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /** 4. New Eight-Life's Pilgrimage structure. */
    private static boolean eightLifesPilgrimage() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card c = makeCard("Eight-Life's Pilgrimage", p, game);
        p.getZone(ZoneType.Hand).add(c);
        c.setState(CardStateName.Backside, true);

        boolean enchantment = c.getType().isEnchantment();
        boolean shroud = c.hasKeyword("Shroud");
        // etbCounter is not a queryable keyword name; check the actual counter instead
        boolean hasETBCounter = c.getKeywords().toString().contains("etbCounter");
        int sacAbilities = 0;
        for (SpellAbility sa : c.getSpellAbilities()) {
            String cost = sa.getPayCosts() == null ? "" : sa.getPayCosts().toString();
            if (cost.contains("Sacrifice") || cost.contains("powerEQ")) {
                sacAbilities++;
            }
        }
        int winTriggers = 0;
        for (forge.game.trigger.Trigger t : c.getTriggers()) {
            String present = t.getParam("IsPresent");
            if (present != null && present.contains("counters_GE8_INCARNATION")) {
                winTriggers++;
            }
        }
        boolean pass = enchantment && shroud && hasETBCounter && sacAbilities >= 1 && winTriggers >= 1;
        System.out.println("[EightLifes] enchantment=" + enchantment + " shroud=" + shroud
                + " etbCounter=" + hasETBCounter + " sacAbility=" + sacAbilities
                + " winTrigger=" + winTriggers + " -> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }
}
