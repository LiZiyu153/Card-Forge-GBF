import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.keyword.Chain;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Headless engine test for the GBF DIY "Chain" keyword (连锁), engine patch P-19.
 *
 * <pre>
 *   K:Chain:&lt;N&gt;:&lt;effect SVar&gt;
 * </pre>
 *
 * <p><b>Semantics (R52, per the designer's clarification).</b> "Whenever a creature you control
 * with power greater than 2 attacks, if this is the Nth such attack, &lt;effect&gt;" is judged on
 * the <i>declaration batch</i> of a combat phase: attacking with N or more qualifying creatures
 * in the same combat phase triggers the effect <b>once</b>. It is therefore judged once per
 * combat phase (a turn with two combat phases can trigger twice), and a batch smaller than N
 * never triggers. Several Chain abilities on one card fire independently.
 *
 * <p><b>Why this file drives the real path.</b> The two shipped versions of this keyword were
 * both wrong because they were validated against a hand-written driver that did not match the
 * engine:
 * <ul>
 *   <li>{@code count == N - 1} fired for every attacker of a two-attacker batch and for none of a
 *       three-attacker batch (the designer saw "Chain 3 fires when exactly two attacked");</li>
 *   <li>a membership-based ordinal then fired for <i>every</i> attacker of a three-attacker batch
 *       (the designer saw "Chain 3 triggered three times").</li>
 * </ul>
 * The reason both were wrong: during the declare-attackers step the stack is frozen, so
 * {@code CombatUtil.checkDeclaredAttacker}'s {@code runTrigger(TriggerType.Attacks, ...)} is
 * parked in {@code TriggerHandler.waitingTriggers} and only checked after the whole batch has been
 * recorded in {@code Player#getCreaturesAttackedThisTurn} — every attacker's trigger therefore
 * sees the full list. The gate no longer reads that list at all: it counts the declaration batch
 * carried by the event. This test reproduces the live sequence (including the frozen stack and the
 * {@code AttackersDeclared} event) in {@link #declareBatch}, and additionally runs the same batch
 * with an unfrozen stack so the check happens per attacker — the outcome must be identical.
 */
public class GbfChainTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testChain1FiresOnSingleAttacker();
        ok &= testChain2NeedsTwoInSameBatch();
        ok &= testChain3SilentOnTwoAttackers();
        ok &= testChain3FiresOnceOnThreeAttackers();
        ok &= testChain3FiresOnceOnFourAttackers();
        ok &= testPower2DoesNotCount();
        ok &= testOpponentAttackersDoNotCount();
        ok &= testIndependentChainKeywords();
        ok &= testJudgedPerCombatPhase();
        ok &= testIndependentOfTriggerCheckTiming();
        ok &= testGateApi();
        ok &= testRealCardScript();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /* ------------------------------------------------------------------ */
    /* the live attack path                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Declares {@code declared} as one attack declaration and drains the result.
     * <p>
     * This mirrors {@code PhaseHandler.declareAttackersTurnBasedAction} (the only live caller):
     * new {@link Combat} for the combat phase &rarr; {@code addAttacker} for every attacker &rarr;
     * {@code GameEventAttackersDeclared} &rarr; {@code AttackersDeclaredOneTarget} per defender
     * &rarr; {@code AttackersDeclared} &rarr; the {@code CombatUtil.checkDeclaredAttacker} loop.
     * The stack is frozen for the duration, exactly like the real step, so triggers are parked in
     * {@code waitingTriggers} and checked when {@code unfreezeStack()} flushes them.
     */
    private static Combat declareBatch(Game game, Player attackingPlayer, Card... declared) {
        return declareBatch(game, attackingPlayer, true, declared);
    }

    private static Combat declareBatch(Game game, Player attackingPlayer, boolean freezeStack, Card... declared) {
        Player defender = attackingPlayer.getOpponents().getFirst();

        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attackingPlayer);
        Combat combat = new Combat(attackingPlayer);
        game.getPhaseHandler().setCombat(combat);
        for (Card c : declared) {
            combat.addAttacker(c, defender);
        }
        registerTriggers(game);

        if (freezeStack) {
            game.getStack().freezeStack(null);
        }

        Multimap<GameEntity, Card> attackersMap = ArrayListMultimap.create();
        for (GameEntity ge : combat.getDefenders()) {
            attackersMap.putAll(ge, combat.getAttackersOf(ge));
        }
        game.fireEvent(new GameEventAttackersDeclared(attackingPlayer, attackersMap));

        for (GameEntity ge : combat.getDefenders()) {
            if (combat.getAttackersOf(ge).isEmpty()) {
                continue;
            }
            Map<AbilityKey, Object> rp = AbilityKey.newMap();
            rp.put(AbilityKey.Attackers, combat.getAttackersOf(ge));
            rp.put(AbilityKey.AttackingPlayer, attackingPlayer);
            rp.put(AbilityKey.AttackedTarget, Collections.singletonList(ge));
            game.getTriggerHandler().runTrigger(TriggerType.AttackersDeclaredOneTarget, rp, false);
        }
        if (!combat.getAttackers().isEmpty()) {
            Map<AbilityKey, Object> rp = AbilityKey.newMap();
            rp.put(AbilityKey.Attackers, combat.getAttackers());
            rp.put(AbilityKey.AttackingPlayer, attackingPlayer);
            rp.put(AbilityKey.AttackedTarget, Collections.singletonList(defender));
            game.getTriggerHandler().runTrigger(TriggerType.AttackersDeclared, rp, false);
        }

        for (Card c : new ArrayList<>(combat.getAttackers())) {
            CombatUtil.checkDeclaredAttacker(game, c, combat, true);
        }
        game.getTriggerHandler().resetActiveTriggers();

        if (freezeStack) {
            game.getStack().unfreezeStack();   // flushes waitingTriggers -> the Chain$ gate runs
        } else {
            game.getTriggerHandler().runWaitingTriggers();
        }
        playUntilStackClear(game);
        return combat;
    }

    private static void registerTriggers(Game game) {
        for (Card bf : game.getCardsIn(ZoneType.Battlefield)) {
            game.getTriggerHandler().registerActiveTrigger(bf, false);
        }
    }

    /** How many abilities of {@code host} are waiting on the stack right now. */
    private static int abilitiesOfOnStack(Game game, Card host) {
        int n = 0;
        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            Card src = sa == null ? null : sa.getHostCard();
            if (src != null && src.getId() == host.getId() && src.getName().equals(host.getName())) {
                n++;
            }
        }
        return n;
    }

    /* ------------------------------------------------------------------ */
    /* card helpers                                                       */
    /* ------------------------------------------------------------------ */

    /** A vanilla permanent of the given type (synthetic card via the public Card API). */
    private static Card makeBody(Game game, Player owner, String name, String types) {
        Card c = new Card(cardId++, game);
        c.setName(name);
        c.setOwner(owner);
        c.setController(owner, game.getNextTimestamp());
        c.setType(forge.card.CardType.parse(types, false));
        c.setGameTimestamp(game.getNextTimestamp());
        return c;
    }

    /** Registers a synthetic card carrying Chain keywords, then adds the K: lines. */
    private static Card makeChainCard(Game game, Player owner, String name, String[] svars, String... keywords) {
        Card c = makeBody(game, owner, name, "Enchantment");
        for (int i = 0; i + 1 < svars.length; i += 2) {
            c.setSVar(svars[i], svars[i + 1]);
        }
        for (String kw : keywords) {
            c.addIntrinsicKeyword(kw);
        }
        return c;
    }

    /** A creature on the battlefield with the given base power. */
    private static Card body(Game game, Player owner, String name, int power) {
        Card c = makeBody(game, owner, name, "Creature Beast");
        c.setBasePower(power);
        c.setBaseToughness(1);
        addToBattlefield(c);
        return c;
    }

    /** The standard "gain 4 life" Chain effect used by most cases below. */
    private static String[] gain4(String svarName) {
        return new String[] { svarName, "DB$ GainLife | Defined$ You | LifeAmount$ 4"
                + " | SpellDescription$ You gain 4 life. (chain)" };
    }

    private static boolean report(String tag, boolean ok, String detail) {
        System.out.println("[" + tag + "] " + detail + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 1. Chain 1 fires for a single qualifying attacker                  */
    /* ------------------------------------------------------------------ */

    private static boolean testChain1FiresOnSingleAttacker() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host A", gain4("TrigChain"), "Chain:1:TrigChain"));

        int before = p.getLife();
        declareBatch(game, p, body(game, p, "Body A1", 3));
        int gained = p.getLife() - before;

        return report("Chain1", gained == 4, "lifeGained=" + gained + " (expect 4)");
    }

    /* ------------------------------------------------------------------ */
    /* 2. Chain 2 needs two qualifying attackers in the same batch        */
    /* ------------------------------------------------------------------ */

    private static boolean testChain2NeedsTwoInSameBatch() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host B", gain4("TrigChain"), "Chain:2:TrigChain"));

        int before = p.getLife();
        declareBatch(game, p, body(game, p, "Body B1", 3));
        boolean silentOnOne = p.getLife() == before;

        declareBatch(game, p, body(game, p, "Body B2", 3), body(game, p, "Body B3", 3));
        int gained = p.getLife() - before;

        return report("Chain2", silentOnOne && gained == 4,
                "silentOnOne=" + silentOnOne + " lifeGainedOnTwo=" + gained + " (expect 0 then 4)");
    }

    /* ------------------------------------------------------------------ */
    /* 3. Chain 3 stays silent for a two-attacker batch                   */
    /* ------------------------------------------------------------------ */

    /** The designer's second report: Chain 3 fired whenever exactly two creatures attacked. */
    private static boolean testChain3SilentOnTwoAttackers() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host C", gain4("TrigChain"), "Chain:3:TrigChain"));

        int before = p.getLife();
        declareBatch(game, p, body(game, p, "Body C1", 3), body(game, p, "Body C2", 4));
        int gained = p.getLife() - before;

        return report("Chain3Two", gained == 0, "lifeGained=" + gained + " (expect 0)");
    }

    /* ------------------------------------------------------------------ */
    /* 4. Chain 3 fires exactly ONCE for a three-attacker batch           */
    /* ------------------------------------------------------------------ */

    /** The designer's first report: Chain 3 "triggered three times". */
    private static boolean testChain3FiresOnceOnThreeAttackers() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host D", gain4("TrigChain"), "Chain:3:TrigChain");
        addToBattlefield(host);

        Card c1 = body(game, p, "Body D1", 3);
        Card c2 = body(game, p, "Body D2", 4);
        Card c3 = body(game, p, "Body D3", 5);

        int before = p.getLife();
        // count the triggers while they sit on the stack, before anything resolves
        Player def = p.getOpponents().getFirst();
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, p);
        Combat combat = new Combat(p);
        game.getPhaseHandler().setCombat(combat);
        for (Card c : new Card[] { c1, c2, c3 }) {
            combat.addAttacker(c, def);
        }
        registerTriggers(game);
        game.getStack().freezeStack(null);
        Map<AbilityKey, Object> rp = AbilityKey.newMap();
        rp.put(AbilityKey.Attackers, combat.getAttackers());
        rp.put(AbilityKey.AttackingPlayer, p);
        rp.put(AbilityKey.AttackedTarget, Collections.singletonList(def));
        game.getTriggerHandler().runTrigger(TriggerType.AttackersDeclared, rp, false);
        for (Card c : new ArrayList<>(combat.getAttackers())) {
            CombatUtil.checkDeclaredAttacker(game, c, combat, true);
        }
        game.getStack().unfreezeStack();
        // unfreezeStack() runs the Chain$ gate and queues the surviving triggers as simultaneous
        // stack entries; move them onto the stack so they can be counted before resolution.
        game.getStack().addAllTriggeredAbilitiesToStack();
        int onStack = abilitiesOfOnStack(game, host);
        playUntilStackClear(game);
        int gained = p.getLife() - before;

        return report("Chain3Three", onStack == 1 && gained == 4,
                "chainTriggersOnStack=" + onStack + " (expect 1) lifeGained=" + gained + " (expect 4)");
    }

    /* ------------------------------------------------------------------ */
    /* 5. "N or more": a bigger batch still fires only once               */
    /* ------------------------------------------------------------------ */

    private static boolean testChain3FiresOnceOnFourAttackers() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host E", gain4("TrigChain"), "Chain:3:TrigChain"));

        int before = p.getLife();
        declareBatch(game, p, body(game, p, "Body E1", 3), body(game, p, "Body E2", 3),
                body(game, p, "Body E3", 3), body(game, p, "Body E4", 3));
        int gained = p.getLife() - before;

        return report("Chain3Four", gained == 4, "lifeGained=" + gained + " (expect 4)");
    }

    /* ------------------------------------------------------------------ */
    /* 6. power 2 or less neither counts nor triggers                      */
    /* ------------------------------------------------------------------ */

    private static boolean testPower2DoesNotCount() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host F", gain4("TrigChain"), "Chain:2:TrigChain"));

        int before = p.getLife();
        // a power-2 attacker must not fill a Chain 1-or-2 slot
        declareBatch(game, p, body(game, p, "Small F1", 2), body(game, p, "Big F2", 3));
        int gainedMixed = p.getLife() - before;
        boolean mixedSilent = gainedMixed == 0;

        // two qualifying ones do fill it, even next to a power-2 body
        declareBatch(game, p, body(game, p, "Big F3", 3), body(game, p, "Big F4", 3),
                body(game, p, "Small F5", 2));
        int gainedAll = p.getLife() - before;

        return report("Power2", mixedSilent && gainedAll == 4,
                "mixed=" + gainedMixed + " (expect 0) withTwoBig=" + gainedAll + " (expect 4)");
    }

    /* ------------------------------------------------------------------ */
    /* 7. the opponent's attackers do not count                           */
    /* ------------------------------------------------------------------ */

    private static boolean testOpponentAttackersDoNotCount() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        addToBattlefield(makeChainCard(game, p, "Chain Host G", gain4("TrigChain"), "Chain:2:TrigChain"));

        int before = p.getLife();
        declareBatch(game, q, body(game, q, "Their G1", 6), body(game, q, "Their G2", 6));
        boolean oppSilent = p.getLife() == before;

        declareBatch(game, p, body(game, p, "Mine G3", 3), body(game, p, "Mine G4", 3));
        int gained = p.getLife() - before;

        return report("Opponent", oppSilent && gained == 4,
                "oppBatchSilent=" + oppSilent + " ourBatchGain=" + gained + " (expect 4)");
    }

    /* ------------------------------------------------------------------ */
    /* 8. several Chain keywords on one card fire independently           */
    /* ------------------------------------------------------------------ */

    private static boolean testIndependentChainKeywords() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host H", new String[] {
                "TrigFirst", "DB$ GainLife | Defined$ You | LifeAmount$ 3"
                        + " | SpellDescription$ You gain 3 life. (chain one)",
                "TrigSecond", "DB$ GainLife | Defined$ You | LifeAmount$ 7"
                        + " | SpellDescription$ You gain 7 life. (chain two)" },
                "Chain:2:TrigFirst", "Chain:3:TrigSecond"));

        int before = p.getLife();
        declareBatch(game, p, body(game, p, "Body H1", 3), body(game, p, "Body H2", 3));
        int afterTwo = p.getLife() - before;   // Chain 2 only
        declareBatch(game, p, body(game, p, "Body H3", 3), body(game, p, "Body H4", 3),
                body(game, p, "Body H5", 3));
        int afterThree = p.getLife() - before; // Chain 2 + Chain 3

        return report("Independent", afterTwo == 3 && afterThree == 13,
                "afterBatchOf2=" + afterTwo + " (expect 3) afterBatchOf3=" + afterThree + " (expect 13)");
    }

    /* ------------------------------------------------------------------ */
    /* 9. judged once per combat phase                                    */
    /* ------------------------------------------------------------------ */

    /** Two combat phases in one turn each get their own batch, so each can trigger. */
    private static boolean testJudgedPerCombatPhase() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host I", gain4("TrigChain"), "Chain:2:TrigChain"));

        int before = p.getLife();
        declareBatch(game, p, body(game, p, "Body I1", 3), body(game, p, "Body I2", 3));
        int afterFirst = p.getLife() - before;
        declareBatch(game, p, body(game, p, "Body I3", 3), body(game, p, "Body I4", 3));
        int afterSecond = p.getLife() - before;

        return report("PerPhase", afterFirst == 4 && afterSecond == 8,
                "firstPhase=" + afterFirst + " (expect 4) total=" + afterSecond + " (expect 8)");
    }

    /* ------------------------------------------------------------------ */
    /* 10. the gate does not depend on when the trigger is checked         */
    /* ------------------------------------------------------------------ */

    /**
     * Same three-attacker batch, but with an <i>unfrozen</i> stack so every trigger is checked
     * immediately (per attacker, with the turn record only partly filled). The result must be
     * identical — this is what the two earlier implementations got wrong.
     */
    private static boolean testIndependentOfTriggerCheckTiming() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        addToBattlefield(makeChainCard(game, p, "Chain Host J", gain4("TrigChain"), "Chain:3:TrigChain"));

        int before = p.getLife();
        declareBatch(game, p, false, body(game, p, "Body J1", 3), body(game, p, "Body J2", 3),
                body(game, p, "Body J3", 3));
        int gained = p.getLife() - before;

        boolean recorded = p.getCreaturesAttackedThisTurn().size() == 3;
        return report("CheckTiming", gained == 4,
                "lifeGained=" + gained + " (expect 4) turnRecordSize=" + recorded + " (expect true)");
    }

    /* ------------------------------------------------------------------ */
    /* 11. engine API + rendering                                         */
    /* ------------------------------------------------------------------ */

    private static boolean testGateApi() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card host = makeChainCard(game, p, "Chain Host K", gain4("TrigChain"), "Chain:3:TrigChain");
        addToBattlefield(host);

        boolean keywordOk = host.hasKeyword(Keyword.CHAIN);
        String reminder = null;
        boolean parseOk = false;
        for (KeywordInterface inst : host.getKeywords(Keyword.CHAIN)) {
            if (inst instanceof Chain chain) {
                reminder = inst.getReminderText();
                parseOk = chain.getAmount() == 3 && "TrigChain".equals(chain.getEffect())
                        && "Chain 3".equals(chain.getTitle());
            }
        }

        Card big1 = body(game, p, "Api K1", 3);
        Card big2 = body(game, p, "Api K2", 5);
        Card big3 = body(game, p, "Api K5", 4);
        Card small = body(game, p, "Api K3", 2);
        Card theirs = body(game, q, "Api K4", 6);
        List<Card> batch = Arrays.asList(big1, small, big2);

        boolean qualifies = Chain.qualifies(p, big1) && !Chain.qualifies(p, small)
                && !Chain.qualifies(p, theirs) && !Chain.qualifies(q, big1);
        boolean counted = Chain.countQualifying(p, batch) == 2;
        boolean ordinals = Chain.qualifyingOrdinal(p, batch, big1) == 1
                && Chain.qualifyingOrdinal(p, batch, big2) == 2
                && Chain.qualifyingOrdinal(p, batch, small) == 0
                && Chain.qualifyingOrdinal(p, batch, theirs) == 0;

        // batch shape (what K:Chain generates): "N or more in the same batch"
        Map<AbilityKey, Object> batch3 = AbilityKey.newMap();
        batch3.put(AbilityKey.Attackers, new forge.game.card.CardCollection(Arrays.asList(big1, big3, big2)));
        Map<AbilityKey, Object> batch2 = AbilityKey.newMap();
        batch2.put(AbilityKey.Attackers, new forge.game.card.CardCollection(Arrays.asList(big1, small, big2)));
        boolean batchGate = Chain.meetsChain(game, p, batch3, "3")
                && Chain.meetsChain(game, p, batch3, "1")
                && !Chain.meetsChain(game, p, batch3, "4")
                && Chain.meetsChain(game, p, batch2, "2")
                && !Chain.meetsChain(game, p, batch2, "3");

        // per-attacker shape (a hand-written T:Mode$ Attacks trigger)
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, p);
        Combat combat = new Combat(p);
        game.getPhaseHandler().setCombat(combat);
        for (Card c : batch) {
            combat.addAttacker(c, p.getOpponents().getFirst());
        }
        Map<AbilityKey, Object> one = AbilityKey.newMap();
        one.put(AbilityKey.Attacker, big2);
        Map<AbilityKey, Object> first = AbilityKey.newMap();
        first.put(AbilityKey.Attacker, big1);
        boolean fallbackGate = Chain.meetsChain(game, p, one, "2")    // big2 is the 2nd qualifying
                && !Chain.meetsChain(game, p, one, "3")
                && !Chain.meetsChain(game, p, first, "2");

        boolean ok = keywordOk && parseOk && qualifies && counted && ordinals && batchGate
                && fallbackGate && reminder != null && reminder.contains("3 or more");
        System.out.println("[API] keyword=" + keywordOk + " parse=" + parseOk + " qualifies=" + qualifies
                + " count=" + counted + " ordinal=" + ordinals + " batchGate=" + batchGate
                + " perAttackerGate=" + fallbackGate
                + " reminder=\"" + reminder + "\" -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 12. the real card script                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The real card script path: "Celestial Guidance" (GBF #268) carries
     * {@code K:Chain:3:TrigChainBolt} with a TARGETED effect.
     * <p>
     * This also guards against the silent failure mode of a missing keyword: an unknown
     * {@code K:} line is filtered without error, so a green GbfParseTest alone does NOT prove
     * the engine knows the keyword. Asserting the keyword instance + generated trigger params
     * does.
     */
    private static boolean testRealCardScript() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card aura = makeCard("Celestial Guidance", p, game);
        addToBattlefield(aura);

        boolean keyword = aura.hasKeyword(Keyword.CHAIN);
        boolean params = false;
        int chainTriggers = 0;
        for (Trigger t : aura.getTriggers()) {
            if (!"3".equals(t.getParam("Chain"))) {
                continue;
            }
            chainTriggers++;
            params = TriggerType.AttackersDeclared == t.getMode()
                    && "You".equals(t.getParam("AttackingPlayer"))
                    && "Creature.YouCtrl+powerGE3".equals(t.getParam("ValidAttackers"))
                    && "GE3".equals(t.getParam("ValidAttackersAmount"))
                    && "TrigChainBolt".equals(t.getParam("Execute"))
                    && t.getOverridingAbility() != null
                    && t.getOverridingAbility().getParam("NumDmg") != null
                    && "Creature.YouDontCtrl".equals(t.getOverridingAbility().getParam("ValidTgts"));
        }

        // the aura half still works
        Card enchanted = body(game, p, "Enchanted", 2);
        aura.attachToEntity(enchanted, null);
        game.getAction().checkStaticAbilities();
        boolean pumped = enchanted.getNetPower() == 4; // 2 + 2

        // two qualifying attackers: silent
        Card victim = body(game, q, "Victim", 5);
        int victimBefore = victim.getDamage();
        declareBatch(game, p, body(game, p, "Body L1", 3), body(game, p, "Body L2", 3));
        boolean silentOnTwo = victim.getDamage() == victimBefore;

        // three qualifying attackers: the chain fires and pings the only legal target
        declareBatch(game, p, body(game, p, "Body L3", 3), body(game, p, "Body L4", 3),
                body(game, p, "Body L5", 3));
        int dealt = victim.getDamage() - victimBefore;

        boolean ok = keyword && params && chainTriggers == 1 && pumped && silentOnTwo && dealt == 2;
        System.out.println("[RealCard] chainKeyword=" + keyword + " triggerParams=" + params
                + " chainTriggers=" + chainTriggers + " enchantedPT=" + enchanted.getNetPower()
                + " silentOnTwo=" + silentOnTwo + " damageDealt=" + dealt
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
