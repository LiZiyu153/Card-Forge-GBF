import java.util.List;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Regression tests for the two bugs reported after v0.0.2.5:
 *
 * 1. Descent of the Flames spent energy but dealt no damage: the target count
 *    depends on the energy paid, which at cast time was still 0 -> no targets.
 *    Fixed with the official "announce at cast time" pattern (Announce$ X +
 *    TargetMax$ X). The redundant ChooseNumber dialog was removed: X is already
 *    fixed by the announcement, so the payment node just pays PayEnergy<X>.
 *
 * 2. Two-Crown's Strife never gained control of the artifact because nothing
 *    selected one any more. The artifact must NOT be a cast-time target (the
 *    user wants the spell castable when the opponent has no artifact), so it is
 *    chosen at RESOLUTION time.
 *
 *    The "when that creature is destroyed this way" clause is now a real
 *    DelayedTrigger (official Time to Feed / Skeletonize pattern) instead of a
 *    Branch on Count$ValidGraveyard. Reason: the whole sub-ability chain
 *    resolves as ONE unit, and the state-based action that puts a lethally
 *    damaged creature into the graveyard only runs after that unit finishes -
 *    so a graveyard count taken inside the chain is always 0 and the branch
 *    never fired (this is why the in-game log showed no artifact step).
 */
public class GbfR47FixesTest extends GbfTestBase {

    public static void main(String[] args) {
        init();
        boolean ok = true;
        ok &= descentNoRedundantChoice();
        ok &= twoCrownsSelectsArtifactAtResolution();
        ok &= twoCrownsFightDeathGainsArtifact();
        ok &= twoCrownsSurvivorDoesNotGainArtifact();
        System.out.println(ok ? "ALL PASS" : "FAILURES PRESENT");
        System.exit(ok ? 0 : 1);
    }

    /** 1. Descent: announce drives X; no leftover ChooseNumber dialog. */
    private static boolean descentNoRedundantChoice() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card c = makeCard("Descent of the Flames", p, game);
        p.getZone(ZoneType.Hand).add(c);

        SpellAbility root = c.getSpellAbilities().get(0);
        SpellAbility damage = root.getSubAbility();
        boolean noChooseNumber = true;
        SpellAbility cur = root;
        while (cur != null) {
            if ("ChooseNumber".equals(cur.getParam("DB"))) {
                noChooseNumber = false;
            }
            cur = cur.getSubAbility();
        }
        boolean announces = root.hasParam("Announce");
        boolean xIsPaid = "Count$xPaid".equals(c.getSVar("X"));
        boolean targetMaxX = damage != null && "X".equals(damage.getParam("TargetMax"));
        boolean dmgOne = damage != null && "1".equals(damage.getParam("NumDmg"));
        boolean paysEnergy = damage != null
                && String.valueOf(damage.getParam("UnlessCost")).contains("PayEnergy<X>");
        boolean pass = noChooseNumber && announces && xIsPaid && targetMaxX && dmgOne && paysEnergy;
        System.out.println("[Descent] announce=" + announces
                + " SVarX=" + c.getSVar("X")
                + " targetMax=" + (damage == null ? "-" : damage.getParam("TargetMax"))
                + " numDmg=" + (damage == null ? "-" : damage.getParam("NumDmg"))
                + " unlessCost=" + (damage == null ? "-" : damage.getParam("UnlessCost"))
                + " leftoverChooseNumber=" + !noChooseNumber
                + " -> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /** Helper: the back-face spell of the modal DFC, as a real SpellAbility. */
    private static SpellAbility backSpell(Player p, Game game) {
        Card c = makeCard("Two-Crown's Strife", p, game);
        p.getZone(ZoneType.Hand).add(c);
        c.setState(CardStateName.Backside, true);
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                return sa;
            }
        }
        return null;
    }

    /**
     * 2a. Two-Crown's Strife is castable with no artifact around: exactly the two
     *     creature targets are chosen at cast time, and the artifact is picked at
     *     resolution time (GainControl over a delayed trigger, not a cast target).
     */
    private static boolean twoCrownsSelectsArtifactAtResolution() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        SpellAbility back = backSpell(p, game);
        if (back == null) {
            System.out.println("[TwoCrowns] no back spell -> FAIL");
            return false;
        }

        boolean gainViaDelayed = false;
        boolean delayedRemembers = false;
        boolean artifactTargetedAtCast = false;
        int castTargetNodes = 0;
        StringBuilder detail = new StringBuilder();

        // Walk the whole chain. The DelayedTrigger's Execute arm is NOT a cast-time
        // target node: its targets are chosen when the trigger resolves, which is
        // exactly what makes the spell castable with no artifact on the battlefield.
        java.util.ArrayDeque<SpellAbility> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<SpellAbility> resolutionArms = new java.util.ArrayDeque<>();
        queue.add(back);
        java.util.Set<SpellAbility> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (!queue.isEmpty()) {
            SpellAbility sa = queue.poll();
            if (!seen.add(sa)) {
                continue;
            }
            if (sa.usesTargeting()) {
                castTargetNodes++;
                String vt = String.valueOf(sa.getParam("ValidTgts"));
                detail.append("[").append(vt).append("] ");
                if (vt.contains("Artifact")) {
                    artifactTargetedAtCast = true;
                }
            }
            if (sa.getSubAbility() != null) {
                queue.add(sa.getSubAbility());
            }
            // Follow DelayedTrigger Execute arms separately: they resolve later.
            String exec = sa.getParam("Execute");
            if (exec != null && "DelayedTrigger".equals(String.valueOf(sa.getParam("DB")))) {
                SpellAbility arm = forge.game.ability.AbilityFactory.getAbility(
                        sa.getHostCard(), exec);
                if (arm != null) {
                    resolutionArms.add(arm);
                }
            }
        }
        for (SpellAbility sa : resolutionArms) {
            if ("GainControl".equals(String.valueOf(sa.getParam("DB")))
                    && String.valueOf(sa.getParam("ValidTgts")).contains("Artifact")) {
                gainViaDelayed = true;
            }
        }
        // The delayed trigger must remember the creature it watches.
        for (SpellAbility sa : seen) {
            if ("DelayedTrigger".equals(String.valueOf(sa.getParam("DB")))
                    && "Targeted".equals(sa.getParam("RememberObjects"))) {
                delayedRemembers = true;
            }
        }
        boolean pass = castTargetNodes == 2 && gainViaDelayed
                && delayedRemembers && !artifactTargetedAtCast;
        System.out.println("[TwoCrowns] castTargetNodes=" + castTargetNodes + " " + detail
                + " gainControlOnDelayedTrigger=" + gainViaDelayed
                + " delayedRemembersObjects=" + delayedRemembers
                + " artifactTargetedAtCast=" + artifactTargetedAtCast
                + " -> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /**
     * 2b. The real chain: the opponent's legendary creature dies to the fight,
     *     the delayed trigger fires and the caster gains control of the
     *     opponent's artifact.
     *
     *     Mirrors what the in-game log showed (fight resolved, no artifact step).
     */
    private static boolean twoCrownsFightDeathGainsArtifact() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        // Isamaru is a vanilla 2/2 legendary: it becomes 4/4 with the +2/+2 and
        // survives; Kytheon is a 2/1 legendary, so the fight kills it.
        Card mine = makeCard("Isamaru, Hound of Konda", p, game);
        Card theirs = makeCard("Kytheon, Hero of Akros", q, game);
        Card artifact = makeCard("Darksteel Ingot", q, game);

        addToBattlefield(mine);
        addToBattlefield(theirs);
        addToBattlefield(artifact);

        SpellAbility back = backSpell(p, game);
        if (back == null) {
            System.out.println("[TwoCrownsFight] no back spell -> FAIL");
            return false;
        }

        back.setActivatingPlayer(p);
        back.getTargets().add(mine);
        SpellAbility chooseOpp = back.getSubAbility();
        chooseOpp.getTargets().add(theirs);
        back.setPayCosts(new forge.game.cost.Cost("0", false));
        game.getAction().moveToStack(back.getHostCard(), back);
        game.getStack().add(back);
        playUntilStackClear(game);

        // Diagnose: what did the delayed trigger end up remembering?
        String remembered = describeDelayedTriggers(game);

        // Let state-based actions push the lethally damaged creature to the yard,
        // then let the delayed trigger fire and resolve.
        game.getPhaseHandler().mainLoopStep();
        runTriggersAndClear(game);

        boolean theirsDead = game.getZoneOf(theirs) != null
                && game.getZoneOf(theirs).is(ZoneType.Graveyard);
        boolean gainControl = artifact.getController() == p;

        // Keep the diagnostic honest: report what actually happened at each step.
        System.out.println("[TwoCrownsFight] mine=" + mine.getName() + "(" + mine.getNetPower()
                + "/" + mine.getNetToughness() + ")"
                + " theirs=" + theirs.getName() + "(" + theirs.getNetPower()
                + "/" + theirs.getNetToughness() + ")"
                + " theirsDead=" + theirsDead
                + " delayed=" + remembered
                + " artifactController=" + (artifact.getController() == null
                        ? "?" : artifact.getController().getName())
                + " gainControl=" + gainControl
                + " -> " + ((theirsDead && gainControl) ? "PASS" : "FAIL"));
        return theirsDead && gainControl;
    }

    /**
     * 2c. Negative case: if the fight does NOT kill the opponent's legend, the
     *     "when that creature is destroyed this way" clause must not hand over
     *     the artifact. Guards against the trigger firing unconditionally.
     */
    private static boolean twoCrownsSurvivorDoesNotGainArtifact() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        // Isamaru (2/2 -> 4/4) fights a 5/5 legendary: the 5/5 survives (4 damage
        // on 5 toughness) and Isamaru dies instead - but Isamaru is NOT the
        // creature the clause watches, so no artifact may change hands.
        Card mine = makeCard("Isamaru, Hound of Konda", p, game);
        Card theirs = makeCard("Kodama of the North Tree", q, game);
        Card artifact = makeCard("Darksteel Ingot", q, game);

        addToBattlefield(mine);
        addToBattlefield(theirs);
        addToBattlefield(artifact);

        SpellAbility back = backSpell(p, game);
        if (back == null) {
            System.out.println("[TwoCrownsSurvivor] no back spell -> FAIL");
            return false;
        }
        back.setActivatingPlayer(p);
        back.getTargets().add(mine);
        back.getSubAbility().getTargets().add(theirs);
        back.setPayCosts(new forge.game.cost.Cost("0", false));
        game.getAction().moveToStack(back.getHostCard(), back);
        game.getStack().add(back);
        playUntilStackClear(game);
        game.getPhaseHandler().mainLoopStep();
        runTriggersAndClear(game);

        boolean theirsAlive = game.getZoneOf(theirs) != null
                && game.getZoneOf(theirs).is(ZoneType.Battlefield);
        boolean noGain = artifact.getController() == q;
        boolean pass = theirsAlive && noGain;
        System.out.println("[TwoCrownsSurvivor] theirs=" + theirs.getName() + "("
                + theirs.getNetPower() + "/" + theirs.getNetToughness() + ")"
                + " theirsAlive=" + theirsAlive
                + " artifactStillOpponents=" + noGain
                + " -> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /** Report the remembered cards of every registered delayed trigger. */
    private static String describeDelayedTriggers(Game game) {
        try {
            java.lang.reflect.Field f =
                    forge.game.trigger.TriggerHandler.class.getDeclaredField("delayedTriggers");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<forge.game.trigger.Trigger> list =
                    (List<forge.game.trigger.Trigger>) f.get(game.getTriggerHandler());
            StringBuilder sb = new StringBuilder("count=" + list.size());
            for (forge.game.trigger.Trigger t : list) {
                sb.append(" {");
                for (Object o : t.getTriggerRemembered()) {
                    sb.append(o instanceof Card ? ((Card) o).getName() : String.valueOf(o))
                            .append(",");
                }
                sb.append("}");
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable(" + e.getClass().getSimpleName() + ")";
        }
    }
}
