import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Headless behavioral test for Andira, Guardian of the West-Southwest (GBF #125)
 * and its attached Prepare spell "Infinite Monkey Hands: Baboon Blast".
 *
 * 1. Andira has Flying and Ninjutsu {1}{U}{B}.
 * 2. Andira enters prepared (Prepare mechanic: K:ETBReplacement -> DBPrepare
 *    sets the Prepared attribute).
 * 3. Static: each legendary creature card in your hand has ninjutsu {2}{U}{B}
 *    (non-legendary creature cards must NOT get it).
 * 4. Baboon Blast chain (A:SP$ RevealHand -> ChooseCard creature ->
 *    ChooseCard noncreature -> Branch EQ2 -> Play one free -> Discard unused):
 *    target player reveals hand, controller picks a creature card and a
 *    noncreature card, may cast one of the two without paying, then the
 *    unused one is discarded.
 */
public class GbfAndiraTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testKeywords();
        ok &= testPrepareChain();
        ok &= testHandNinjutsuGrant();
        ok &= testBaboonBlastChain();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /** Andira has Flying and Ninjutsu keywords. */
    private static boolean testKeywords() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card andira = makeCard("Andira, Guardian of the West-Southwest", p, game);
        boolean flying = false, ninjutsu = false;
        for (KeywordInterface kw : andira.getKeywords()) {
            if (kw.getKeyword() == Keyword.FLYING) {
                flying = true;
            }
            if (kw.getKeyword() == Keyword.NINJUTSU) {
                ninjutsu = true;
                System.out.println("[Keywords] Ninjutsu cost: " + kw.getReminderText());
            }
        }
        boolean ok = flying && ninjutsu;
        System.out.println("[Keywords] flying=" + flying + " ninjutsu=" + ninjutsu + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * Drives the real scripted DBPrepare chain (K:ETBReplacement target) and
     * checks Andira becomes prepared (isPrepared()).
     */
    private static boolean testPrepareChain() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card andira = makeCard("Andira, Guardian of the West-Southwest", p, game);
        addToBattlefield(andira);

        SpellAbility prepare = AbilityFactory.getAbility(andira, "DBPrepare");
        prepare.setActivatingPlayer(p);
        game.getStack().add(prepare);
        playUntilStackClear(game);

        boolean ok = andira.isPrepared();
        System.out.println("[Prepare] isPrepared=" + ok + " preparedSpell=" + andira.getPreparedSpell()
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * Static: each legendary creature card in your hand has ninjutsu {2}{U}{B}.
     * Non-legendary creature cards must NOT receive it.
     */
    private static boolean testHandNinjutsuGrant() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card andira = makeCard("Andira, Guardian of the West-Southwest", p, game);
        addToBattlefield(andira);

        Card legendary = makeCard("Isamaru, Hound of Konda", p, game); // legendary 2/2 dog
        Card nonLegendary = makeCard("Elvish Mystic", p, game);        // not legendary
        addToHand(legendary);
        addToHand(nonLegendary);

        game.getAction().checkStaticAbilities();

        boolean legendaryGot = false;
        for (KeywordInterface kw : legendary.getKeywords()) {
            if (kw.getKeyword() == Keyword.NINJUTSU) {
                legendaryGot = true;
                System.out.println("[HandGrant] legendary " + legendary.getName() + " Ninjutsu: " + kw.getReminderText());
            }
        }
        boolean nonLegendaryExcluded = true;
        for (KeywordInterface kw : nonLegendary.getKeywords()) {
            if (kw.getKeyword() == Keyword.NINJUTSU) {
                nonLegendaryExcluded = false;
            }
        }
        boolean ok = legendaryGot && nonLegendaryExcluded;
        System.out.println("[HandGrant] legendaryGotNinjutsu=" + legendaryGot
                + " nonLegendaryExcluded=" + nonLegendaryExcluded + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * Baboon Blast: target player reveals hand; controller picks a creature
     * card and a noncreature card; may cast one of the two without paying;
     * then the unused one is discarded. We drive the real A:SP$ chain
     * (target = opponent q whose hand has 1 creature + 1 noncreature) and
     * assert both cards leave q's hand.
     */
    private static boolean testBaboonBlastChain() {
        Game game = newGame();
        Player p = game.getPlayers().get(1); // controller / chooser
        Player q = game.getPlayers().get(0); // target player

        // Baboon Blast is the attached spell of Andira's Prepare mechanic
        Card andira = makeCard("Andira, Guardian of the West-Southwest", p, game);
        addToBattlefield(andira);
        SpellAbility prepare = AbilityFactory.getAbility(andira, "DBPrepare");
        prepare.setActivatingPlayer(p);
        game.getStack().add(prepare);
        playUntilStackClear(game);
        Card bb = andira.getPreparedSpell();

        Card creature = makeCard("Grizzly Bears", q, game);
        Card nonCreature = makeCard("Lightning Bolt", q, game);
        addToHand(creature);
        addToHand(nonCreature);

        int handBefore = q.getCardsIn(ZoneType.Hand).size();
        SpellAbility sa = bb.getFirstSpellAbility(); // A:SP$ RevealHand ...
        System.out.println("[BaboonBlast] spellAbility api=" + sa.getApi()
                + " subAbility=" + sa.getParam("SubAbility"));
        sa.setActivatingPlayer(p);
        sa.getTargets().add(q);
        game.getStack().add(sa);
        int guard = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
            guard++;
            if (guard > 800) break;
        } while (!game.isGameOver() && !game.getStack().isEmpty());

        int handAfter = q.getCardsIn(ZoneType.Hand).size();
        System.out.println("[BaboonBlast] handBefore=" + handBefore + " handAfter=" + handAfter
                + " qHand=" + q.getCardsIn(ZoneType.Hand)
                + " remembered=" + bb.getRemembered()
                + " creatureZone=" + creature.getZone().getZoneType()
                + " nonCreatureZone=" + nonCreature.getZone().getZoneType()
                + " stackEmpty=" + game.getStack().isEmpty());
        boolean ok = handAfter == 0 && handBefore == 2;
        System.out.println("[BaboonBlast] -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
