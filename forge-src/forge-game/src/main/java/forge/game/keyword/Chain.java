package forge.game.keyword;

import java.util.Map;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;

/**
 * GBF DIY keyword: Chain (连锁).
 * <p>
 * Oracle text: {@code Chain N — <effect>} — "Whenever a creature you control with power 3 or
 * greater attacks, if this is the Nth time that a creature you control with power 3 or greater
 * has attacked, <effect>."
 * <p>
 * <b>Judging rule (R52, per the designer's clarification).</b> The engine declares all attackers
 * of a combat phase at once, so "the Nth attack" cannot be read off the individual creatures —
 * the check is made on the <i>declaration batch</i>: if the number of creatures you control with
 * power greater than 2 that you declared as attackers in the same combat phase is <b>N or more</b>,
 * the ability triggers <b>once</b>. It is therefore judged once per combat phase: a turn with two
 * combat phases can trigger the same Chain ability twice, and a batch smaller than N never does.
 * Several Chain abilities on one card each get their own trigger and fire independently.
 * <p>
 * Card script syntax (K: line):<br>
 * {@code K:Chain:<N>:<effect SVar>}<br>
 * e.g. {@code K:Chain:3:TrigChainBolt} + {@code SVar:TrigChainBolt:DB$ DealDamage | ...} — exactly
 * the shape the official {@code K:Chapter} keyword uses. The effect may target normally: the SVar
 * named by {@code Execute$} is the trigger's overriding ability, so targets are chosen when it is
 * put on the stack.
 * <p>
 * The engine turns it into
 * {@code T:Mode$ AttackersDeclared | AttackingPlayer$ You | ValidAttackers$ Creature.YouCtrl+powerGE3
 * | ValidAttackersAmount$ GE<N> | TriggerZones$ Battlefield | Chain$ <N> | Execute$ <effect SVar>},
 * using the engine's native "whenever you attack with N or more <filter> creatures" trigger
 * (the same shape ~30 official cards use, e.g. Escarpment Fortress / Inniaz). The {@code Chain$}
 * parameter is re-evaluated by {@link #meetsChain} from {@code TriggerHandler.canRunTrigger}.
 * <p>
 * <b>Why not a per-attacker {@code Attacks} trigger (two earlier attempts, both wrong).</b>
 * {@code CombatUtil.checkDeclaredAttacker} calls {@code runTrigger(TriggerType.Attacks, ...)}
 * <i>before</i> {@code addCreaturesAttackedThisTurn}, which looks like a per-attacker ordering —
 * but during the declare-attackers step the stack is frozen, so the trigger is parked in
 * {@code TriggerHandler.waitingTriggers} and only <i>checked</i> once the step ends. By then the
 * whole batch is recorded and every attacker's trigger sees the same list, so any gate that
 * compares that list against N lets <i>all</i> of the batch's triggers through: with three
 * attackers a {@code Chain 3} ability fired three times (and with the older
 * {@code count == N - 1} gate it fired for every attacker of a two-attacker batch and for none of
 * a three-attacker batch). Counting the declaration batch is the only reading that is stable
 * under that deferral.
 */
public class Chain extends KeywordInstance<Chain> {
    private int amount;
    private String effect = "";   // SVar name holding the effect the chain runs

    @Override
    public int getAmount() {
        return amount;
    }

    /** SVar name of the effect executed when the chain fires. */
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
        return reminderText.replace("%s", String.valueOf(amount));
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

    /** True when {@code c} counts for a Chain ability controlled by {@code controller}. */
    public static boolean qualifies(final Player controller, final Card c) {
        return controller != null && c != null && c.isCreature()
                && controller.equals(c.getController()) && c.getNetPower() >= MIN_POWER;
    }

    /**
     * How many members of {@code cards} are "a creature you control with power greater than 2".
     * {@code cards} is normally the batch of attackers declared in the current combat
     * ({@code Combat#getAttackers()} / {@code AbilityKey.Attackers}).
     */
    public static int countQualifying(final Player controller, final Iterable<Card> cards) {
        int n = 0;
        if (cards == null) {
            return 0;
        }
        for (final Card c : cards) {
            if (qualifies(controller, c)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 1-based position of {@code attacker} among the qualifying members of {@code cards}, or 0
     * when it is not one of them. Used by the per-attacker fallback below.
     */
    public static int qualifyingOrdinal(final Player controller, final Iterable<Card> cards, final Card attacker) {
        if (cards == null || attacker == null) {
            return 0;
        }
        int n = 0;
        for (final Card c : cards) {
            if (!qualifies(controller, c)) {
                continue;
            }
            n++;
            if (c.getId() == attacker.getId() && c.getName().equals(attacker.getName())) {
                return n;
            }
        }
        return 0;
    }

    /**
     * Evaluates a {@code Chain$ <N>} trigger gate.
     * <p>
     * Two runParams shapes are accepted so the keyword also works on hand-written triggers:
     * <ul>
     *   <li>{@code AbilityKey.Attackers} (what {@code K:Chain} generates, via
     *       {@code T:Mode$ AttackersDeclared}): true when at least N of the declared attackers
     *       qualify. This is the "you attacked with N or more at the same time" check, and the
     *       trigger itself fires only once per declare-attackers step.</li>
     *   <li>{@code AbilityKey.Attacker} (a hand-written {@code T:Mode$ Attacks} trigger): true
     *       only for the Nth qualifying attacker of the combat that is declaring, so the ability
     *       still fires at most once per combat phase. Needs a live combat
     *       ({@code game.getCombat()}); prefer the generated form.</li>
     * </ul>
     *
     * @param game           the game, used to read the current combat in the fallback shape
     * @param hostController controller of the card that has the Chain ability (the "you" of
     *                       "creatures you control")
     * @param runParams      the trigger event's parameters
     * @param condition      the raw {@code Chain$} parameter, i.e. N
     */
    public static boolean meetsChain(final Game game, final Player hostController,
            final Map<AbilityKey, Object> runParams, final String condition) {
        if (hostController == null || runParams == null) {
            return false;
        }
        final int needed = parseAmount(condition);
        if (needed < 1) {
            return false;
        }

        // generated shape: the whole declaration batch is in the event
        final Object batchParam = runParams.get(AbilityKey.Attackers);
        if (batchParam instanceof Iterable<?>) {
            int n = 0;
            for (final Object o : (Iterable<?>) batchParam) {
                if (o instanceof Card && qualifies(hostController, (Card) o)) {
                    n++;
                }
            }
            return n >= needed;
        }

        // fallback shape: one attacker per event
        final Object attackerParam = runParams.get(AbilityKey.Attacker);
        if (!(attackerParam instanceof Card) || !qualifies(hostController, (Card) attackerParam)) {
            return false;
        }
        final Combat combat = game == null ? null : game.getCombat();
        if (combat == null) {
            return false;
        }
        return qualifyingOrdinal(hostController, combat.getAttackers(), (Card) attackerParam) == needed;
    }

    private static int parseAmount(final String condition) {
        if (condition == null || condition.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(condition.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Renders the generated trigger description for a Chain N ability. */
    public static String getTriggerDescription(final int amount) {
        return "Chain " + amount + " \u2014 Triggers when you attack with " + amount
                + " or more creatures you control with power greater than 2 in the same combat phase.";
    }
}
