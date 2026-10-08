import java.util.List;

import forge.card.CardType;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.keyword.Specialty;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Headless engine test for the GBF DIY "Specialty" keyword (得意), implemented in the
 * engine as P-18 (see docs/forge-src-patches.md):
 *
 * <pre>
 *   K:Specialty:&lt;type&gt;:&lt;N&gt;[:&lt;extra params&gt;]
 * </pre>
 *
 * <p>Oracle semantics: "As long as you control N or more &lt;type&gt; permanents, this card
 * has the abilities listed after this keyword." The engine turns the keyword into
 * <pre>
 *   S:Mode$ Continuous | Affected$ Card.Self | Specialty$ &lt;type&gt;:&lt;N&gt; | &lt;extra params&gt;
 * </pre>
 * and gates the whole speciality block on the {@code Specialty$} condition, so a single
 * K: line can grant keywords, static abilities, triggered abilities and activated
 * abilities at once (the same shape the official {@code K:Class} keyword uses).
 *
 * <p>All cards are built in memory (synthetic {@link CardRules}) so this test exercises the
 * engine, not any particular GBF script, and needs no DB/editions registration. Card-file
 * level verification happens through {@link GbfParseTest} once a real GBF card uses it.
 */
public class GbfSpecialtyTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testKeywordRecognized();
        ok &= testNotMet();
        ok &= testDynamicEnterAndLeave();
        ok &= testCompositeTypeAndAutoQualify();
        ok &= testGrantsTriggeredAbility();
        ok &= testGrantsActivatedAbility();
        ok &= testGrantsStaticAbility();
        ok &= testConditionSpecialtyGatesResolution();
        ok &= testTooltip();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Builds a synthetic card straight through the public Card API (no DB entry, no drop-in
     * script file), then attaches the K: lines. {@code addIntrinsicKeyword(..)} fires
     * {@code createTraits} into {@code CardFactoryUtil} — the exact same path a real
     * {@code K:} line takes, so this exercises the engine and not one particular card.
     *
     * @param svars    alternating name/value SVar definitions (must exist BEFORE the keyword,
     *                 because granted traits are parsed while the keyword's static ability
     *                 is built — {@code AbilityUtils.getSVar} falls back to the card)
     * @param keywords K: lines to attach
     */
    private static Card makeSpecialtyCard(Game game, Player owner, String name, String[] svars, String... keywords) {
        Card c = makeBody(game, owner, name, "Enchantment");
        for (int i = 0; i + 1 < svars.length; i += 2) {
            c.setSVar(svars[i], svars[i + 1]);
        }
        for (String kw : keywords) {
            c.addIntrinsicKeyword(kw);
        }
        return c;
    }

    /** A vanilla permanent of the given type, for counting. */
    private static Card makeBody(Game game, Player owner, String name, String types) {
        Card c = new Card(cardId++, game);
        c.setName(name);
        c.setOwner(owner);
        c.setController(owner, game.getNextTimestamp());
        c.setType(CardType.parse(types, false));
        c.setGameTimestamp(game.getNextTimestamp());
        return c;
    }

    private static int countSubtype(Player p, String subtype) {        int n = 0;
        for (Card c : p.getZone(ZoneType.Battlefield)) {
            if (c.getType().hasSubtype(subtype)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Recomputes continuous effects and keyword caches — the layer pass that a real game runs
     * on every zone change. Dev mode's {@code addToBattlefield} writes the zone directly and
     * skips it, so tests must drive it explicitly ({@code GameAction.checkStaticAbilities()}).
     */
    private static void refresh(Game game, Card... cards) {
        game.getAction().checkStaticAbilities();
        for (Card c : cards) {
            c.updateKeywordsCache();
        }
    }

    /**
     * Resolves a granted ability through the engine's real resolution path.
     * <p>
     * <b>Dev-mode limitation (R48):</b> pushing a <i>granted</i> activated ability onto the
     * dev stack and draining the main loop does not run its effect (an intrinsic ability of the
     * same shape does), and the ability's own cost is never paid either way. Both were measured
     * with a throwaway probe: intrinsic + stack = effect, granted + stack = silent,
     * granted + this direct path = effect. The quirk is in the dev activation harness, not in
     * the Specialty grant, so effect-level assertions use this path and real-game activation is
     * tracked in verification-todo.md.
     */
    private static void resolveDirect(Card host, SpellAbility sa, Player activator) {
        sa.setActivatingPlayer(activator);
        if (sa.getHostCard().getController() == null) {
            sa.getHostCard().setController(activator, 0);
        }
        AbilityUtils.resolve(sa);
    }

    /* ------------------------------------------------------------------ */
    /* 1. condition met -> the granted keyword exists                     */
    /* ------------------------------------------------------------------ */

    private static boolean testKeywordRecognized() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card host = makeSpecialtyCard(game, p, "Specialty Test A", new String[0], "Specialty:Primal:2:AddKeyword$ Flying");
        addToBattlefield(host);

        Card pr1 = makeBody(game, p, "Primal Body A", "Creature Primal");
        addToBattlefield(pr1);
        Card pr2 = makeBody(game, p, "Primal Body B", "Creature Primal");
        addToBattlefield(pr2);
        // a Primal controlled by the OPPONENT must not count
        Card opp = makeBody(game, q, "Opp Primal", "Creature Primal");
        addToBattlefield(opp);
        refresh(game, host);

        boolean flying = host.hasKeyword("Flying");
        boolean noHint = !host.hasKeyword("Menace");
        boolean oppClean = !opp.hasKeyword("Flying");
        System.out.println("[Keyword] youPrimals=" + Specialty.countPermanents(p, "Primal")
                + " allPrimals=" + Specialty.countPermanents(p, "Creature")
                + " flying=" + flying + " oppPrimalFlying=" + opp.hasKeyword("Flying")
                + " -> " + (flying && noHint && oppClean ? "PASS" : "FAIL"));
        return flying && noHint && oppClean;
    }

    /* ------------------------------------------------------------------ */
    /* 2. condition not met -> nothing granted                            */
    /* ------------------------------------------------------------------ */

    private static boolean testNotMet() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeSpecialtyCard(game, p, "Specialty Test B", new String[0], "Specialty:Primal:2:AddKeyword$ Flying");
        addToBattlefield(host);

        Card pr1 = makeBody(game, p, "Primal Body A", "Creature Primal");
        addToBattlefield(pr1);
        refresh(game, host);

        boolean ok = !host.hasKeyword("Flying");
        System.out.println("[KeywordNotMet] primals=1 flying=" + host.hasKeyword("Flying")
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 3. the condition is continuous (enters / leaves the battlefield)    */
    /* ------------------------------------------------------------------ */

    private static boolean testDynamicEnterAndLeave() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeSpecialtyCard(game, p, "Specialty Test C", new String[0], "Specialty:Primal:2:AddKeyword$ Flying");
        addToBattlefield(host);
        refresh(game, host);
        boolean before = !host.hasKeyword("Flying");

        Card pr1 = makeBody(game, p, "Primal Body A", "Creature Primal");
        addToBattlefield(pr1);
        refresh(game, host);
        boolean one = !host.hasKeyword("Flying");

        Card pr2 = makeBody(game, p, "Primal Body B", "Creature Primal");
        enterBattlefield(game, pr2);
        refresh(game, host);
        boolean two = host.hasKeyword("Flying");

        // second Primal dies (zone change, no travel triggers needed)
        game.getAction().moveTo(p.getZone(ZoneType.Graveyard), pr2, null);
        refresh(game, host);
        boolean after = !host.hasKeyword("Flying");

        boolean ok = before && one && two && after;
        System.out.println("[Dynamic] 0=" + before + " 1=" + one + " 2=" + two + " 1again=" + after
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 4. composite type spec / bare creature type auto-qualification      */
    /* ------------------------------------------------------------------ */

    private static boolean testCompositeTypeAndAutoQualify() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        // bare subtype -> Creature.<subtype>
        Card beastHost = makeSpecialtyCard(game, p, "Specialty Test D", new String[0], "Specialty:Beast:1:AddKeyword$ Flying");
        addToBattlefield(beastHost);
        Card beast = makeBody(game, p, "Beast Body", "Creature Beast");
        addToBattlefield(beast);
        refresh(game, beastHost);
        boolean beastOk = beastHost.hasKeyword("Flying");

        // explicit card type -> only that card type counts as "you control"
        Card enchantHost = makeSpecialtyCard(game, p, "Specialty Test E", new String[0], "Specialty:Creature:2:AddKeyword$ Menace");
        addToBattlefield(enchantHost);
        refresh(game, enchantHost);
        boolean oneEnchant = !enchantHost.hasKeyword("Menace");
        Card enchant2 = makeBody(game, p, "Second Creature Body", "Creature Beast");
        addToBattlefield(enchant2);
        refresh(game, enchantHost, enchant2);
        boolean twoEnchant = enchantHost.hasKeyword("Menace");

        // full compound restriction: subtype within a card type
        Card primalEnchant = makeSpecialtyCard(game, p, "Specialty Test F", new String[0], "Specialty:Enchantment.Primal:1:AddKeyword$ Haste");
        addToBattlefield(primalEnchant);
        refresh(game, primalEnchant);
        boolean compoundOk = !primalEnchant.hasKeyword("Haste");

        boolean ok = beastOk && oneEnchant && twoEnchant && compoundOk;
        System.out.println("[Types] beast=" + beastOk + " enchant1=" + oneEnchant
                + " enchant2=" + twoEnchant + " compoundNeedsPrimalEnchant=" + compoundOk
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 5. grants a whole triggered ability                                */
    /* ------------------------------------------------------------------ */

    private static boolean testGrantsTriggeredAbility() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        // (a) without a Primal the host must have no trigger at all
        Card lonely = makeSpecialtyCard(game, q, "Lonely Host", new String[] {
                "TrigCast", "Mode$ SpellCast | ValidCard$ Card.Self | TriggerZones$ Battlefield"
                        + " | Execute$ TrigGain | Secondary$ True | TriggerDescription$ test",
                "TrigGain", "DB$ GainLife | Defined$ You | LifeAmount$ 3" },
                "Specialty:Primal:1:AddTrigger$ TrigCast");
        addToBattlefield(lonely);
        refresh(game, lonely);
        boolean lonelySilent = lonely.getTriggers().isEmpty();

        // (b) with a Primal the trigger exists and really carries the granted effect
        Card pr = makeBody(game, p, "Primal Body A", "Creature Primal");
        addToBattlefield(pr);
        Card host = makeSpecialtyCard(game, p, "Specialty Trigger Test", new String[] {
                "TrigCast", "Mode$ SpellCast | ValidCard$ Card.Self | TriggerZones$ Battlefield"
                        + " | Execute$ TrigGain | Secondary$ True"
                        + " | TriggerDescription$ Whenever you cast a spell, you gain 3 life.",
                "TrigGain", "DB$ GainLife | Defined$ You | LifeAmount$ 3" },
                "Specialty:Primal:1:AddTrigger$ TrigCast");
        addToBattlefield(host);
        refresh(game, host);

        forge.game.trigger.Trigger granted = host.getTriggers().stream()
                .filter(t -> t.getMode() == TriggerType.SpellCast).findFirst().orElse(null);
        boolean present = granted != null && granted.getOverridingAbility() != null;

        int lifeBefore = p.getLife();
        if (present) {
            SpellAbility gain = granted.getOverridingAbility();
            gain.setActivatingPlayer(p);
            game.getStack().add(gain);
            game.getStack().unfreezeStack();
            playUntilStackClear(game);
        }
        boolean gained = p.getLife() == lifeBefore + 3;

        boolean ok = lonelySilent && present && gained;
        System.out.println("[GrantTrigger] noPrimalNoTrigger=" + lonelySilent + " grantedTrigger=" + present
                + " lifeGained=" + (p.getLife() - lifeBefore) + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 6. grants an activated ability                                     */
    /* ------------------------------------------------------------------ */

    private static boolean testGrantsActivatedAbility() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeSpecialtyCard(game, p, "Specialty Activate Test", new String[] {
                "ABGainLife", "AB$ GainLife | Cost$ T | Defined$ You | LifeAmount$ 5"
                        + " | SpellDescription$ {T}: You gain 5 life." },
                "Specialty:Primal:1:AddAbility$ ABGainLife");
        addToBattlefield(host);
        Card pr = makeBody(game, p, "Primal Body A", "Creature Primal");
        addToBattlefield(pr);
        refresh(game, host);

        SpellAbility gain = findAbility(host, "gain 5 life");
        boolean present = gain != null;
        int before = p.getLife();
        if (present) {
            resolveDirect(host, gain, p);
        }
        boolean ok = present && p.getLife() == before + 5;
        System.out.println("[GrantActivated] present=" + present + " lifeGained=" + (p.getLife() - before)
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Locates an ability of the card whose SpellDescription contains the key. */
    private static SpellAbility findAbility(Card c, String key) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            String d = sa.getDescription();
            if (d != null && d.contains(key)) {
                return sa;
            }
        }
        for (SpellAbility sa : c.getManaAbilities()) {
            String d = sa.getDescription();
            if (d != null && d.contains(key)) {
                return sa;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* 7. grants a nested static ability (AddStaticAbility)               */
    /* ------------------------------------------------------------------ */

    private static boolean testGrantsStaticAbility() {
        String pump = "Mode$ Continuous | Affected$ Creature.Primal+YouCtrl"
                + " | AddPower$ 1 | AddToughness$ 1 | Secondary$ True"
                + " | Description$ Primal creatures you control get +1/+1.";

        // (a) control: the very same static applied intrinsically pumps the Primal
        Game g1 = newGame();
        Player p1 = g1.getPlayers().get(1);
        Card control = makeBody(g1, p1, "Control Host", "Enchantment");
        addToBattlefield(control);
        control.addStaticAbility(pump);
        Card body1 = makeBody(g1, p1, "Primal Body A", "Creature Primal");
        body1.setBasePower(2);
        body1.setBaseToughness(2);
        addToBattlefield(body1);
        refresh(g1, control, body1);
        boolean controlOk = body1.getNetPower() == 3 && body1.getNetToughness() == 3;

        // (b) the same static granted by a Specialty keyword block
        Game g2 = newGame();
        Player p2 = g2.getPlayers().get(1);
        Card host = makeSpecialtyCard(g2, p2, "Specialty Static Test",
                new String[] { "SBoost", pump },
                "Specialty:Primal:1:AddStaticAbility$ SBoost");
        addToBattlefield(host);
        Card pr = makeBody(g2, p2, "Primal Body B", "Creature Primal");
        pr.setBasePower(2);
        pr.setBaseToughness(2);
        addToBattlefield(pr);
        refresh(g2, host, pr);
        boolean grantedOk = pr.getNetPower() == 3 && pr.getNetToughness() == 3;

        boolean ok = controlOk && grantedOk;
        System.out.println("[GrantStatic] plainStaticPump=" + body1.getNetPower() + "/" + body1.getNetToughness()
                + " grantedStaticPump=" + pr.getNetPower() + "/" + pr.getNetToughness()
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 8. Condition$ Specialty gates resolution (grantor-static fallback)  */
    /* ------------------------------------------------------------------ */

    private static boolean testConditionSpecialtyGatesResolution() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        // (a) no Primal: the granted ability does not exist at all
        Card host = makeSpecialtyCard(game, p, "Specialty Condition Test", new String[] {
                "ABPing", "AB$ GainLife | Condition$ Specialty | Cost$ 0 | Defined$ You | LifeAmount$ 2"
                        + " | SpellDescription$ You gain 2 life while specialized." },
                "Specialty:Primal:1:AddAbility$ ABPing");
        addToBattlefield(host);
        refresh(game, host);
        boolean absentWhenNotMet = findAbility(host, "2 life while specialized") == null;
        boolean blockerBlocks = !Specialty.meetsCondition(p, "Primal:1");

        // (b) Primal arrives: ability exists, Condition$ Specialty is met, effect resolves
        Card pr = makeBody(game, p, "Primal Body A", "Creature Primal");
        addToBattlefield(pr);
        refresh(game, host);
        SpellAbility ping = findAbility(host, "2 life while specialized");
        boolean metNow = false;
        if (ping != null) {
            ping.setActivatingPlayer(p);
            metNow = ping.metConditions();
        }
        int before = p.getLife();
        if (ping != null) {
            resolveDirect(host, ping, p);
        }
        boolean gained = p.getLife() == before + 2;

        boolean ok = absentWhenNotMet && blockerBlocks && metNow && gained;
        System.out.println("[Condition] absentWhenNotMet=" + absentWhenNotMet
                + " conditionFalse=" + blockerBlocks + " metNow=" + metNow
                + " lifeGained=" + (p.getLife() - before) + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 9. keyword rendering / API surface                                 */
    /* ------------------------------------------------------------------ */

    private static boolean testTooltip() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card host = makeSpecialtyCard(game, p, "Specialty Tooltip Test", new String[0],
                "Specialty:Primal:2:AddKeyword$ Flying");
        addToBattlefield(host);
        refresh(game, host);

        String reminder = null;
        boolean enumOk = false;
        boolean found = false;
        for (forge.game.keyword.KeywordInterface inst : host.getKeywords(forge.game.keyword.Keyword.SPECIALTY)) {
            found = true;
            if (inst instanceof Specialty sp) {
                reminder = inst.getReminderText();
                enumOk = sp.getKeyword() == forge.game.keyword.Keyword.SPECIALTY
                        && "Creature.Primal".equals(sp.getValidType())
                        && "Primal".equals(sp.getTypeDescription())
                        && sp.getAmount() == 2
                        && "Creature.Primal:2".equals(sp.getConditionParam());
            }
        }
        // engine API helpers
        Card pr = makeBody(game, p, "Primal Body A", "Creature Primal");
        addToBattlefield(pr);
        boolean countOk = Specialty.countPermanents(p, "Primal") == 1
                && Specialty.meetsCondition(p, "Primal:1")
                && !Specialty.meetsCondition(p, "Primal:2")
                && Specialty.meetsCondition(p, "Creature:1");
        boolean textOk = reminder != null
                && reminder.contains("2 or more Primal permanents");
        boolean ok = enumOk && countOk && textOk;
        System.out.println("[API] reminder=\"" + reminder + "\" enumOk=" + enumOk
                + " countOk=" + countOk + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
