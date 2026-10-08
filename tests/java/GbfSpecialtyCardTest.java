import forge.game.Game;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.zone.ZoneType;

/**
 * R50 verification: the REAL card script of "Kimi to Boku no Concert of Miracles"
 * after switching its Specialty clause from the raw form
 * ({@code S:Mode$ Continuous | IsPresent$ Creature.YouCtrl | PresentCompare$ GE4})
 * to the new engine keyword {@code K:Specialty:Creature:4:AddStaticAbility$ SGraveDredge}.
 *
 * <p>This is the end-to-end check for engine patch P-18 that a synthetic-card test cannot
 * give: it proves the keyword survives the full card-script path (parse -> K: line ->
 * createTraits -> CardFactoryUtil -> generated static -> nested AddStaticAbility -> the
 * granted Dredge keyword + its replacement effect), on a LIVE card, gated by a real count.
 *
 * <p>Covers:
 *  1. the Specialty keyword and its generated static ability exist on the card;
 *  2. below the gate (3 creatures) creature cards in the graveyard gain nothing;
 *  3. at the gate (4 creatures) they really gain Dredge 5 — keyword AND the draw
 *     replacement effect the Dredge keyword builds ("CARDNAME - Dredge 5");
 *  4. it is a continuous condition: dropping back to 3 creatures removes it again;
 *  5. the opponent's graveyard creatures are never affected;
 *  6. the unrelated first clause (first creature spell each turn has convoke) still works.
 */
public class GbfSpecialtyCardTest extends GbfTestBase {

    private static final String CARD = "Kimi to Boku no Concert of Miracles";

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testKeywordWiring();
        ok &= testGatedBothWays();
        ok &= testConvokeClauseUntouched();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /** A creature card owned by {@code owner}, parked in the graveyard. */
    private static Card graveCreature(Game game, Player owner, String name) {
        Card c = makeCard(name, owner, game);
        owner.getZone(ZoneType.Graveyard).add(c);
        return c;
    }

    /** A creature on the battlefield. */
    private static Card battlefieldCreature(Game game, Player owner, String name) {
        Card c = makeCard(name, owner, game);
        addToBattlefield(c);
        return c;
    }

    private static int countDredgeReplacements(Card c) {
        int n = 0;
        for (ReplacementEffect re : c.getReplacementEffects()) {
            String d = re.getDescription();
            if (d != null && d.contains("Dredge")) {
                n++;
            }
        }
        return n;
    }

    /**
     * Recomputes continuous effects and keyword caches — the layer pass a real game runs on
     * every zone change. Dev mode's direct zone writes skip it, so tests drive it explicitly.
     */
    private static void refresh(Game game, Card... cards) {
        game.getAction().checkStaticAbilities();
        for (Card c : cards) {
            c.updateKeywordsCache();
        }
    }

    /* ------------------------------------------------------------------ */

    /** The K: line must produce the Specialty keyword + its generated gated static. */
    private static boolean testKeywordWiring() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card enchant = makeCard(CARD, p, game);
        addToBattlefield(enchant);

        boolean kw = enchant.hasKeyword(Keyword.SPECIALTY);
        boolean staticCount = enchant.getStaticAbilities().size() >= 2;

        boolean ok = kw && staticCount;
        System.out.println("[Wiring] specialty=" + kw + " statics=" + enchant.getStaticAbilities().size()
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * The real gate: below 4 creatures nothing is granted; at 4 creatures the graveyard
     * creatures really have Dredge 5 (keyword + replacement); dropping back removes it;
     * the opponent's graveyard is never touched.
     */
    private static boolean testGatedBothWays() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        Card enchant = makeCard(CARD, p, game);
        addToBattlefield(enchant);

        Card mine = graveCreature(game, p, "Grizzly Bears");
        Card theirs = graveCreature(game, q, "Grizzly Bears");

        Card c1 = battlefieldCreature(game, p, "Grizzly Bears");
        Card c2 = battlefieldCreature(game, p, "Grizzly Bears");
        Card c3 = battlefieldCreature(game, p, "Grizzly Bears");
        refresh(game, enchant, mine, theirs);

        boolean belowKeyword = !mine.hasKeyword("Dredge:5") && !mine.hasKeyword(Keyword.DREDGE);
        boolean belowRepl = countDredgeReplacements(mine) == 0;

        Card c4 = battlefieldCreature(game, p, "Grizzly Bears");
        refresh(game, enchant, mine, theirs);

        boolean atKeyword = mine.hasKeyword(Keyword.DREDGE);
        boolean atRepl = countDredgeReplacements(mine) == 1;
        boolean oppUntouched = !theirs.hasKeyword(Keyword.DREDGE) && countDredgeReplacements(theirs) == 0;

        // leave again -> condition is continuous
        game.getAction().moveTo(p.getZone(ZoneType.Graveyard), c4, null);
        refresh(game, enchant, mine, theirs);
        boolean afterKeyword = !mine.hasKeyword(Keyword.DREDGE) && countDredgeReplacements(mine) == 0;

        boolean ok = belowKeyword && belowRepl && atKeyword && atRepl && oppUntouched && afterKeyword;
        System.out.println("[Gated] 3creatures: noKeyword=" + belowKeyword + " noRepl=" + belowRepl
                + " | 4creatures: kw=" + atKeyword + " repl=" + atRepl
                + " | oppClean=" + oppUntouched
                + " | backTo3: removed=" + afterKeyword
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** The unrelated first clause (convoke on the first creature spell) must still work. */
    private static boolean testConvokeClauseUntouched() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card enchant = makeCard(CARD, p, game);
        addToBattlefield(enchant);

        Card spell = makeCard("Grizzly Bears", p, game);
        spell.setController(p, 0);
        // NOTE: Stack is a GAME-level zone — Player.getZone(ZoneType.Stack) is null
        // (that NPE is what made this test produce an error dialog once); use the game zone.
        game.getStackZone().add(spell);
        refresh(game, enchant, spell);

        boolean convoke = spell.hasKeyword(Keyword.CONVOKE);
        System.out.println("[ConvokeClause] creature spell on stack has convoke=" + convoke
                + " -> " + (convoke ? "PASS" : "FAIL"));
        return convoke;
    }
}
