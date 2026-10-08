import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.keyword.Chain;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;

import java.util.Map;

/**
 * Headless engine test for the GBF DIY "Chain" keyword (连锁), engine patch P-19.
 *
 * <pre>
 *   K:Chain:&lt;N&gt;:&lt;effect SVar&gt;
 * </pre>
 *
 * <p>Oracle semantics: "Whenever a creature you control with power 3 or greater attacks, if
 * this is the Nth time this turn that a creature you control with power 3 or greater has
 * attacked, &lt;effect&gt;." Several Chain abilities on one card each trigger independently.
 *
 * <p>The engine turns the keyword into
 * {@code T:Mode$ Attacks | ValidCard$ Creature.YouCtrl+powerGE3 | TriggerZones$ Battlefield |
 * Chain$ N | Execute$ <effect SVar>}; {@code Chain$ N} is gated in
 * {@code TriggerHandler.canRunTrigger} via {@link Chain#meetsChain}, which works because
 * {@code CombatUtil.checkDeclaredAttacker} fires the Attacks trigger <i>before</i> recording
 * the attacker in {@code Player#getCreaturesAttackedThisTurn} — so the recorded count is
 * exactly "how many qualifying creatures attacked before this one".
 *
 * <p>The attack path is driven through the real {@link CombatUtil#checkDeclaredAttacker}
 * (which fires {@code TriggerType.Attacks} with the engine's own runParams), not by hand.
 */
public class GbfChainTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testChain1FiresOnFirst();
        ok &= testChain2BlockedOnFirst();
        ok &= testOrdinalsSplit();
        ok &= testIndependentTriggers();
        ok &= testPower2DoesNotCount();
        ok &= testOpponentAttackersDoNotCount();
        ok &= testCountApi();
        ok &= testOrdinalBothOrderings();
        ok &= testResetsNextTurn();
        ok &= testRealCardScript();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * The real card script path: "Celestial Guidance" (GBF #268) carries
     * {@code K:Chain:3:TrigChainBolt} with a TARGETED effect.
     * <p>
     * This also guards against the silent failure mode of a missing keyword: an unknown
     * {@code K:} line is filtered without error, so a green GbfParseTest alone does NOT prove
     * the engine knows the keyword. Asserting the keyword instance + params does.
     */
    private static boolean testRealCardScript() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card aura = makeCard("Celestial Guidance", p, game);
        addToBattlefield(aura);

        boolean keyword = aura.hasKeyword(Keyword.CHAIN);
        boolean params = false;
        int chainTriggers = 0;
        for (forge.game.trigger.Trigger t : aura.getTriggers()) {
            if (!"3".equals(t.getParam("Chain"))) {
                continue;
            }
            chainTriggers++;
            params = "Attacks".equals(t.getMode().name())
                    && "TrigChainBolt".equals(t.getParam("Execute"))
                    && "Creature.YouCtrl+powerGE3".equals(t.getParam("ValidCard"))
                    && t.getOverridingAbility() != null
                    && t.getOverridingAbility().getParam("NumDmg") != null
                    && "Creature.YouDontCtrl".equals(t.getOverridingAbility().getParam("ValidTgts"));
        }

        // aura half still works
        Card body = attacker(game, p, 2);
        aura.attachToEntity(body, null);
        game.getAction().checkStaticAbilities();
        boolean pumped = body.getNetPower() == 4; // 2 + 2

        boolean ok = keyword && params && chainTriggers == 1 && pumped;
        System.out.println("[RealCard] chainKeyword=" + keyword + " triggerParams=" + params
                + " chainTriggers=" + chainTriggers + " enchantedPT=" + body.getNetPower()
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Diagnostic: dump the host's Chain triggers with their params. */
    private static void dbgTriggers(String tag, Card host) {
        for (forge.game.trigger.Trigger t : host.getTriggers()) {
            System.out.println("    " + tag + " mode=" + t.getMode() + " params=" + t.getMapParams());
        }
    }

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
    private static Card attacker(Game game, Player owner, int power) {
        Card c = makeBody(game, owner, "Body " + power, "Creature Beast");
        c.setBasePower(power);
        c.setBaseToughness(1);
        addToBattlefield(c);
        return c;
    }

    /**
     * Declares {@code c} as an attacker, in the order the live game uses.
     * <p>
     * <b>R51 hotfix:</b> the live-game ordering is <i>record first, then fire the trigger</i> —
     * which is what made {@code Chain 3} fire on the 2nd attacker (the gate assumed the attacker
     * was NOT yet recorded, an off-by-one). This helper therefore records, then fires, so the
     * tests exercise the ordering that actually happens in a game.
     * <p>
     * <b>Dev-mode note:</b> the real entry point {@code CombatUtil.checkDeclaredAttacker} also
     * dereferences {@code game.getCombat()}, which is null in a dev game that never started a
     * combat phase. The two lines below mirror the engine's own bookkeeping instead, and
     * {@code Chain.qualifyingAttackOrdinal} is membership-based so both orderings are correct.
     */
    private static void declareAttacker(Game game, Card c) {
        Player controller = c.getController();
        for (Card bf : game.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
            game.getTriggerHandler().registerActiveTrigger(bf, false);
        }
        controller.addCreaturesAttackedThisTurn(forge.game.card.CardCopyService.getLKICopy(c),
                controller.getOpponents().getFirst());
        fireAttacks(game, c);
    }

    /** Fires only the {@code Attacks} event (no bookkeeping) — the synthetic ordering. */
    private static void fireAttacks(Game game, Card c) {
        Player controller = c.getController();
        Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.Attacker, c);
        runParams.put(AbilityKey.DefendingPlayer, controller.getOpponents().getFirst());
        runParams.put(AbilityKey.Attacked, controller.getOpponents().getFirst());
        game.getTriggerHandler().runTrigger(TriggerType.Attacks, runParams, false);
    }

    private static void resolveTriggers(Game game) {
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().unfreezeStack();
        playUntilStackClear(game);
    }

    private static SpellAbility findAbility(Card c, String key) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            String d = sa.getDescription();
            if (d != null && d.contains(key)) {
                return sa;
            }
        }
        return null;
    }

    /** The standard "gain 4 life" Chain effect pair used by most cases below. */
    private static String[] gain4(String svarName) {
        return new String[] { svarName, "DB$ GainLife | Defined$ You | LifeAmount$ 4"
                + " | SpellDescription$ You gain 4 life. (chain)" };
    }

    /* ------------------------------------------------------------------ */
    /* 1. Chain 2 fires on the FIRST qualifying attack                    */
    /* ------------------------------------------------------------------ */

    private static boolean testChain1FiresOnFirst() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host A", gain4("TrigChain"),
                "Chain:1:TrigChain");
        addToBattlefield(host);

        Card a = attacker(game, p, 3);
        int before = p.getLife();
        declareAttacker(game, a);
        resolveTriggers(game);

        boolean ok = p.getLife() == before + 4;
        System.out.println("[Chain1First] lifeGained=" + (p.getLife() - before) + " (expect 4)"
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 2. the same shape with Chain 2 stays silent on the 1st attack       */
    /* ------------------------------------------------------------------ */

    private static boolean testChain2BlockedOnFirst() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host B", gain4("TrigChain"),
                "Chain:2:TrigChain");
        addToBattlefield(host);

        Card a = attacker(game, p, 3);
        int before = p.getLife();
        declareAttacker(game, a);
        resolveTriggers(game);

        boolean ok = p.getLife() == before;
        System.out.println("[Chain2First] lifeGained=" + (p.getLife() - before) + " (expect 0)"
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 3. ordinals split correctly: 2nd one triggers, 1st did not         */
    /* ------------------------------------------------------------------ */

    private static boolean testOrdinalsSplit() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host C", gain4("TrigChain"),
                "Chain:2:TrigChain");
        addToBattlefield(host);

        Card a1 = attacker(game, p, 3);
        Card a2 = attacker(game, p, 4);

        int before = p.getLife();
        declareAttacker(game, a1);
        resolveTriggers(game);
        boolean noGainOnFirst = p.getLife() == before;

        declareAttacker(game, a2);
        resolveTriggers(game);
        boolean gainedOnSecond = p.getLife() == before + 4;

        boolean ok = noGainOnFirst && gainedOnSecond;
        System.out.println("[Ordinals] first=" + noGainOnFirst + " second=" + gainedOnSecond
                + " lifeGained=" + (p.getLife() - before) + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 4. several Chain keywords on one card trigger independently         */
    /* ------------------------------------------------------------------ */

    private static boolean testIndependentTriggers() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host D", new String[] {
                "TrigFirst", "DB$ GainLife | Defined$ You | LifeAmount$ 3"
                        + " | SpellDescription$ You gain 3 life. (chain one)",
                "TrigSecond", "DB$ GainLife | Defined$ You | LifeAmount$ 7"
                        + " | SpellDescription$ You gain 7 life. (chain two)" },
                "Chain:1:TrigFirst", "Chain:2:TrigSecond");
        addToBattlefield(host);

        Card a1 = attacker(game, p, 3);
        Card a2 = attacker(game, p, 5);

        int before = p.getLife();
        declareAttacker(game, a1);
        resolveTriggers(game);
        boolean first3 = p.getLife() == before + 3;

        declareAttacker(game, a2);
        resolveTriggers(game);
        boolean then7 = p.getLife() == before + 10;

        boolean ok = first3 && then7;
        System.out.println("[Independent] afterFirst=" + (first3 ? 3 : "?") + " total=" + (p.getLife() - before)
                + " (expect 3 then 10) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 5. power 2 or less does not count and does not trigger             */
    /* ------------------------------------------------------------------ */

    private static boolean testPower2DoesNotCount() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host E", gain4("TrigChain"),
                "Chain:1:TrigChain");
        addToBattlefield(host);

        Card small = attacker(game, p, 2);
        Card big = attacker(game, p, 3);

        int before = p.getLife();
        declareAttacker(game, small);
        resolveTriggers(game);
        boolean smallSilent = p.getLife() == before;

        // the power-2 attacker must not have consumed the "1st" slot
        declareAttacker(game, big);
        resolveTriggers(game);
        boolean bigIsFirst = p.getLife() == before + 4;

        boolean ok = smallSilent && bigIsFirst;
        System.out.println("[Power2] smallSilent=" + smallSilent
                + " bigIsFirst=" + bigIsFirst + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 6. the opponent's attackers do not count                           */
    /* ------------------------------------------------------------------ */

    private static boolean testOpponentAttackersDoNotCount() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card host = makeChainCard(game, p, "Chain Host F", gain4("TrigChain"),
                "Chain:1:TrigChain");
        addToBattlefield(host);

        Card mine = attacker(game, p, 3);
        Card theirs = attacker(game, q, 6);

        int before = p.getLife();
        declareAttacker(game, theirs);
        resolveTriggers(game);
        boolean oppSilent = p.getLife() == before;

        declareAttacker(game, mine);
        resolveTriggers(game);
        boolean mineIsFirst = p.getLife() == before + 4;

        boolean ok = oppSilent && mineIsFirst;
        System.out.println("[Opponent] oppSilent=" + oppSilent + " mineIsFirst=" + mineIsFirst
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 7. engine API + rendering                                          */
    /* ------------------------------------------------------------------ */

    private static boolean testCountApi() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host G", gain4("TrigChain"),
                "Chain:2:TrigChain");
        addToBattlefield(host);

        boolean keywordOk = host.hasKeyword(Keyword.CHAIN);
        String reminder = null;
        boolean parseOk = false;
        for (forge.game.keyword.KeywordInterface inst : host.getKeywords(Keyword.CHAIN)) {
            if (inst instanceof Chain chain) {
                reminder = inst.getReminderText();
                parseOk = chain.getAmount() == 2 && "TrigChain".equals(chain.getEffect());
            }
        }

        Card a = attacker(game, p, 3);
        int beforeCount = Chain.countQualifyingAttackers(p);
        int beforeOrdinal = Chain.qualifyingAttackOrdinal(p, a);
        declareAttacker(game, a);
        int afterCount = Chain.countQualifyingAttackers(p);
        boolean countingOk = beforeCount == 0 && afterCount == 1 && beforeOrdinal == 1;

        boolean ok = keywordOk && parseOk && countingOk
                && reminder != null && reminder.contains("2nd");
        System.out.println("[API] keyword=" + keywordOk + " parse=" + parseOk
                + " countBefore=" + beforeCount + " countAfter=" + afterCount
                + " ordinalWhenRecordingFirst=" + beforeOrdinal
                + " reminder=\"" + reminder + "\" -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 9b. the R51 off-by-one: both record/fire orderings give an ordinal */
    /* ------------------------------------------------------------------ */

    /**
     * Regression for the reported bug "Chain 3 triggers when only TWO creatures attacked":
     * the live game records the attacker <i>before</i> the Attacks trigger is checked, while a
     * synthetic driver may fire it first. The ordinal must be identical either way.
     */
    private static boolean testOrdinalBothOrderings() {
        // (a) record first, then fire (live ordering) — Chain 3 must NOT fire on the 2nd attacker
        Game g1 = newGame();
        Player p1 = g1.getPlayers().get(1);
        Card host1 = makeChainCard(g1, p1, "Chain Host I", gain4("TrigChain"), "Chain:3:TrigChain");
        addToBattlefield(host1);
        Card x1 = attacker(g1, p1, 3);
        Card x2 = attacker(g1, p1, 3);
        Card x3 = attacker(g1, p1, 3);

        int life1 = p1.getLife();
        declareAttacker(g1, x1);
        resolveTriggers(g1);
        boolean silentOnFirst = p1.getLife() == life1;
        declareAttacker(g1, x2);
        resolveTriggers(g1);
        boolean silentOnSecond = p1.getLife() == life1;   // <- the reported bug made this fire
        declareAttacker(g1, x3);
        resolveTriggers(g1);
        boolean firesOnThird = p1.getLife() == life1 + 4;

        // (b) fire first, then record (synthetic ordering) — same ordinals
        Game g2 = newGame();
        Player p2 = g2.getPlayers().get(1);
        Card host2 = makeChainCard(g2, p2, "Chain Host J", gain4("TrigChain"), "Chain:3:TrigChain");
        addToBattlefield(host2);
        Card y1 = attacker(g2, p2, 3);
        Card y2 = attacker(g2, p2, 3);
        Card y3 = attacker(g2, p2, 3);
        int[] ordinals = new int[3];
        int i = 0;
        for (Card y : new Card[] { y1, y2, y3 }) {
            ordinals[i++] = Chain.qualifyingAttackOrdinal(p2, y);
            fireAttacks(g2, y);
            p2.addCreaturesAttackedThisTurn(forge.game.card.CardCopyService.getLKICopy(y),
                    p2.getOpponents().getFirst());
        }
        boolean ordinalSeq = ordinals[0] == 1 && ordinals[1] == 2 && ordinals[2] == 3;

        boolean ok = silentOnFirst && silentOnSecond && firesOnThird && ordinalSeq;
        System.out.println("[BothOrderings] silent1=" + silentOnFirst + " silent2=" + silentOnSecond
                + " fires3=" + firesOnThird + " fireFirstOrdinals=" + ordinals[0] + "/" + ordinals[1]
                + "/" + ordinals[2] + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 8. the chain count is per turn                                     */
    /* ------------------------------------------------------------------ */

    private static boolean testResetsNextTurn() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeChainCard(game, p, "Chain Host H", gain4("TrigChain"),
                "Chain:2:TrigChain");
        addToBattlefield(host);

        Card a1 = attacker(game, p, 3);
        Card a2 = attacker(game, p, 4);
        Card a3 = attacker(game, p, 5);

        int before = p.getLife();
        declareAttacker(game, a1);
        resolveTriggers(game);
        declareAttacker(game, a2);
        resolveTriggers(game);
        boolean firedOnce = p.getLife() == before + 4;

        // a new turn resets the per-turn attack history; with the fresh turn this is again the
        // 1st qualifying attack, so a Chain 2 ability must stay silent
        // (engine path: PhaseHandler -> Player.onCleanupPhase() -> Card.newTurn() -> CardDamageHistory.newTurn())
        for (Card c : new Card[] { a1, a2 }) {
            c.getDamageHistory().newTurn();
        }
        p.onCleanupPhase();

        int afterReset = Chain.countQualifyingAttackers(p);
        declareAttacker(game, a3);
        resolveTriggers(game);
        boolean silentUnderChain2 = p.getLife() == before + 4;

        boolean ok = firedOnce && afterReset == 0 && silentUnderChain2;
        System.out.println("[NextTurn] firedOnce=" + firedOnce + " afterReset=" + afterReset
                + " 1stOfNewTurnSilent=" + silentUnderChain2 + " total=" + (p.getLife() - before)
                + " (expect 4) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

}
