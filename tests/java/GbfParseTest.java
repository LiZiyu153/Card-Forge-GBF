import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import forge.card.CardDb;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * Headless GBF card parse test (authoritative: CardFactory.getCard with cardId >= 0).
 * Usage: java -Dfile.encoding=UTF-8 -cp "<install jar>;<classes>" GbfParseTest [cardName...]
 * Run from the install dir; see GbfTestBase for the run rules.
 */
public class GbfParseTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        CardDb db = FModel.getMagicDb().getCommonCards();

        Set<String> names = new TreeSet<>();
        if (args.length > 0) {
            for (String a : args) {
                names.add(a);
            }
        } else {
            for (PaperCard pc : db.getAllCards()) {
                if ("GBF".equals(pc.getEdition())) {
                    names.add(pc.getName());
                }
            }
        }

        int ok = 0;
        List<String> fails = new ArrayList<>();
        for (String name : names) {
            PaperCard pc = db.getCard(name, "GBF");
            if (pc == null) {
                pc = db.getCard(name);
            }
            if (pc == null) {
                fails.add(name + " (no paper card found)");
                continue;
            }
            try {
                Card c = CardFactory.getCard(pc, null, 1, null);
                if (c == null) {
                    fails.add(name + " (null card)");
                    continue;
                }
                ok++;
                if ("Anthuria, Dancer of the Sun".equals(name)) {
                    System.out.println("ANTHURIA parsed OK");
                    System.out.println("  triggers = " + c.getTriggers().size());
                    System.out.println("  statics  = " + c.getStaticAbilities().size());
                    System.out.println("  keywords = " + c.getKeywords());
                    System.out.println("  SVar MaxMV = " + c.getSVar("MaxMV"));
                    System.out.println("  SVar MinMV = " + c.getSVar("MinMV"));
                    System.out.println("  SVar X     = " + c.getSVar("X"));
                    System.out.println("  SVar Y     = " + c.getSVar("Y"));
                }
            } catch (Throwable t) {
                fails.add(name + " -> " + t);
                t.printStackTrace(System.out);
            }
        }

        System.out.println("RESULT: ok=" + ok + " fail=" + fails.size());
        for (String f : fails) {
            System.out.println("FAIL: " + f);
        }
        System.exit(fails.isEmpty() ? 0 : 1);
    }
}
