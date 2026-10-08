package forge.game.keyword;

import forge.game.card.Card;
import forge.game.player.Player;

/**
 * GBF DIY keyword: Chain (连锁).
 * <p>
 * Oracle text: {@code Chain N — <effect>} — "Whenever a creature you control with power 3 or
 * greater attacks, if this is the Nth time this turn that a creature you control with power 3
 * or greater has attacked, <effect>." Reminder: "(Triggers when the Nth creature you control
 * with power greater than 2 attacks this turn.)"
 * <p>
 * Card script syntax (K: line):<br>
 * {@code K:Chain:<N>:<effect SVar>}<br>
 * e.g. {@code K:Chain:1:TrigFirst} + {@code SVar:TrigFirst:DB$ Draw | Defined$ You | NumCards$ 1}
 * — exactly the shape the official {@code K:Chapter} keyword uses.
 * <p>
 * The engine turns it into
 * {@code T:Mode$ Attacks | ValidCard$ Creature.YouCtrl+powerGE3 | TriggerZones$ Battlefield |
 * Chain$ N | Execute$ <effect SVar>} plus a generated trigger description; the {@code Chain$}
 * parameter is evaluated at trigger time by {@code Trigger.performTest} via
 * {@link #meetsChain}. Several Chain keywords on one card each get their own trigger and fire
 * independently, as required.
 */
public class Chain extends KeywordInstance<Chain> {
    private int amount;
    private String effect = "";   // SVar name holding the effect the chain runs

    @Override
    public int getAmount() {
        return amount;
    }

    /** SVar name of the effect executed on the Nth qualifying attack. */
    public String getEffect() {
        return effect;
    }

    public String getTitle() {
        return getKeyword() + " " + amount;
    }

    public static String getTitle(final int amount) {
        return "Chain " + amount;
    }

    @Override
    protected void parse(String details) {
        // details = "<N>:<effect SVar>"
        final String[] k = details.split(":", 2);
        if (k.length < 2) {
            throw new IllegalArgumentException("Chain keyword needs <N>:<effect SVar>, got: " + details);
        }
        amount = Integer.parseInt(k[0].trim());
        effect = k[1].trim();
    }

    @Override
    protected String formatReminderText(String reminderText) {
        return reminderText.replace("%s", ordinal(amount));
    }

    /* ------------------------------------------------------------------ */
    /* shared helpers — also used by the engine trigger check             */
    /* ------------------------------------------------------------------ */

    /**
     * Power threshold for a qualifying attacker. "力量大于2" is power &gt; 2, which for the
     * integer power values Forge supports is exactly "power 3 or greater"
     * ({@code powerGE3} in valid-card terms).
     */
    public static final int MIN_POWER = 3;

    /**
     * How many creatures {@code controller} controls that have already been recorded as
     * attackers this turn and had power &gt; 2 at that moment.
     * <p>
     * <b>Whether the currently-declared attacker is included depends on where the caller runs</b>,
     * so use {@link #qualifyingAttackOrdinal} instead of comparing this against {@code N} directly.
     */
    public static int countQualifyingAttackers(final Player controller) {
        int n = 0;
        for (final Card c : controller.getCreaturesAttackedThisTurn()) {
            if (c.getController() == controller && c.getNetPower() >= MIN_POWER) {
                n++;
            }
        }
        return n;
    }

    /**
     * The ordinal of {@code attacker} among this turn's qualifying attacks — i.e. "this is the
     * Nth creature you control with power &gt; 2 that attacked", counting from 1.
     * <p>
     * The engine records an attacker in {@code Player#getCreaturesAttackedThisTurn} on two
     * different orderings, and both must yield the same ordinal:
     * <ul>
     *   <li><b>R51 hotfix:</b> in the live game the live attacker IS already recorded by the time
     *       the {@code Attacks} trigger is checked (that is what made {@code Chain 3} fire on the
     *       2nd attacker — an off-by-one), so the recorded list already includes it;</li>
     *   <li>older/synthetic drivers fire the trigger <i>before</i> recording, so it must be added
     *       by hand.</li>
     * </ul>
     * Detecting membership makes both orderings correct instead of guessing one.
     */
    public static int qualifyingAttackOrdinal(final Player controller, final Card attacker) {
        if (controller == null || attacker == null) {
            return 0;
        }
        boolean alreadyRecorded = false;
        for (final Card c : controller.getCreaturesAttackedThisTurn()) {
            if (attacker.getId() == c.getId() && attacker.getName().equals(c.getName())) {
                alreadyRecorded = true;
                break;
            }
        }
        return countQualifyingAttackers(controller) + (alreadyRecorded ? 0 : 1);
    }

    /**
     * Evaluates a {@code Chain$ <N>} trigger gate: true when the attacker in this event is the
     * Nth creature controlled by the trigger's controller with power &gt; 2 to attack this turn
     * (exact match, so a Chain ability fires exactly once per qualifying ordinal — a later, higher
     * ordinal does not re-trigger it).
     *
     * @param hostController controller of the card that has the Chain ability (the "you" of
     *                       "creatures you control")
     * @param attacker       the creature that just attacked (may be an LKI copy)
     */
    public static boolean meetsChain(final Player hostController, final Card attacker, final String condition) {
        if (hostController == null || attacker == null || condition == null || condition.trim().isEmpty()) {
            return false;
        }
        final int needed;
        try {
            needed = Integer.parseInt(condition.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (needed < 1) {
            return false;
        }
        // the attacker must itself qualify: a creature you control with power greater than 2
        if (!attacker.isCreature() || attacker.getNetPower() < MIN_POWER
                || attacker.getController() != hostController) {
            return false;
        }
        return qualifyingAttackOrdinal(hostController, attacker) == needed;
    }

    /** Renders the generated trigger description for a Chain N ability. */
    public static String getTriggerDescription(final int amount) {
        return "Chain " + amount + " \u2014 Triggers when the " + ordinal(amount)
                + " creature you control with power greater than 2 attacks in a combat phase.";
    }

    /** {@code 1st} / {@code 2nd} / {@code 3rd} / {@code 4th} ... */
    private static String ordinal(final int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }
}
