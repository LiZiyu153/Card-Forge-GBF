import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.CostAdjustment;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Headless regression test for Minenwerfer (GBF) conditional cost reduction:
 * "Minenwerfer costs {2} less to cast if an opponent controls creatures with
 * total toughness 6 or greater."
 *
 * Original bug (fixed in 0.0.1.6): SVar:X was "Count$Min 2 0/CompareY GE6.1.0"
 * — "Min 2 0" is not an xCount function and "CompareY" is not a doXMath
 * operator, so X silently evaluated to 0 and the reduction NEVER applied.
 * Fixed with the official Orysa, Tide Choreographer pattern:
 *   S:Mode$ ReduceCost | ... | Amount$ 2 | CheckSVar$ X | SVarCompare$ GE6
 *   SVar:X:Count$Valid Creature.OppCtrl$CardToughness
 * These tests run the real CostAdjustment.adjust() path (the same code path
 * the game uses when paying for the spell) and assert the adjusted CMC.
 */
public class GbfBigBerthaTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testReducedWhenToughnessAtLeast6();
        ok &= testNotReducedWhenBelow6();
        ok &= testNotReducedWhenNoCreatures();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /** Adjusted CMC of Minenwerfer (base {3}{R} = CMC 4) while q controls 'bears' Grizzly Bears (2/2 each). */
    private static int adjustedCmc(Game game, Player p, Player q, int bears) {
        Card bertha = makeCard("Minenwerfer", p, game);
        addToHand(bertha);
        for (int i = 0; i < bears; i++) {
            Card bear = makeCard("Grizzly Bears", q, game);
            addToBattlefield(bear);
        }
        SpellAbility sa = bertha.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        ManaCostBeingPaid cost = new ManaCostBeingPaid(bertha.getManaCost());
        CostAdjustment.adjust(cost, sa, p, null, true, false);
        return cost.getConvertedManaCost();
    }

    /** 3 bears = total toughness 6 -> reduced by 2: CMC 4 - 2 = 2. */
    private static boolean testReducedWhenToughnessAtLeast6() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        int cmc = adjustedCmc(game, p, q, 3);
        boolean ok = cmc == 2;
        System.out.println("[ReducedAt6] 3 bears (total toughness 6) -> adjusted CMC = " + cmc
                + " (expect 2) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** 2 bears = total toughness 4 < 6 -> no reduction: CMC stays 4. */
    private static boolean testNotReducedWhenBelow6() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        int cmc = adjustedCmc(game, p, q, 2);
        boolean ok = cmc == 4;
        System.out.println("[NoReductionBelow6] 2 bears (total toughness 4) -> adjusted CMC = " + cmc
                + " (expect 4) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** No opponent creatures -> no reduction: CMC stays 4. */
    private static boolean testNotReducedWhenNoCreatures() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        int cmc = adjustedCmc(game, p, q, 0);
        boolean ok = cmc == 4;
        System.out.println("[NoReductionNoCreatures] no creatures -> adjusted CMC = " + cmc
                + " (expect 4) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
