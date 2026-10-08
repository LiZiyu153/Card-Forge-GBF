package forge.game.keyword;

import forge.card.CardType;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * GBF DIY keyword: Specialty (得意).
 * <p>
 * Oracle text: {@code <type> Specialty <N>} — "As long as you control N or more <type>
 * permanents, this card has the abilities listed after it."
 * <p>
 * Card script syntax (K: line):<br>
 * {@code K:Specialty:<type>:<N>[:<extra params>]}<br>
 * where {@code <type>} is a valid card restriction (card type such as {@code Creature},
 * a creature type such as {@code Beast}, or a compound one such as {@code Enchantment.Primal};
 * a bare subtype is qualified with {@code Creature.} automatically), and the optional
 * {@code <extra params>} block lists the granted traits exactly like the official
 * {@code K:Class} keyword does, e.g.
 * {@code K:Specialty:Primal:2:AddKeyword$ Haste} or
 * {@code K:Specialty:Primal:2:AddStaticAbility$ SPump | AddTrigger$ TrigFoo}.
 * <p>
 * The engine builds a continuous static ability out of it:
 * {@code Mode$ Continuous | Affected$ Card.Self | Specialty$ <type>:<N> | <extra params>}
 * — the {@code Specialty$} parameter gates the whole continuous ability, so the granted
 * traits only exist while the condition holds.
 */
public class Specialty extends KeywordInstance<Specialty> {
    private int amount;
    private String type = "";       // valid restriction, e.g. "Creature.Beast"
    private String rawType = "";    // as written by the card author, e.g. "Beast"
    private String descType = "";   // display form of the type, e.g. "Beast"

    @Override
    public int getAmount() {
        return amount;
    }

    public String getValidType() {
        return type;
    }

    /** The type exactly as written in the K: line, e.g. {@code Beast}. */
    public String getRawType() {
        return rawType;
    }

    /** Display form of the counted type, e.g. {@code Beast} for {@code Creature.Beast}. */
    public String getTypeDescription() {
        return descType;
    }

    /** The condition parameter consumed by {@code CardTraitBase}/{@code StaticAbility}. */
    public String getConditionParam() {
        return type + ":" + amount;
    }

    public String getTitle() {
        return descType + " " + getKeyword() + " " + amount;
    }

    @Override
    protected void parse(String details) {
        // details = "<type>:<amount>[:<extra params>]"
        final String[] k = details.split(":", 3);
        if (k.length < 2) {
            throw new IllegalArgumentException("Specialty keyword needs <type>:<amount>, got: " + details);
        }
        type = toValidType(k[0].trim());
        descType = toDescType(k[0].trim());
        rawType = k[0].trim();
        amount = Integer.parseInt(k[1].trim());
    }

    @Override
    protected String formatReminderText(String reminderText) {
        return reminderText
                .replace("%d", String.valueOf(amount))
                .replace("%s", descType);
    }

    /* ------------------------------------------------------------------ */
    /* shared helpers — also used by the engine condition checks          */
    /* ------------------------------------------------------------------ */

    /**
     * Normalizes the author written type into a valid card restriction.
     * <p>
     * {@code Creature} → {@code Creature}; {@code Beast} → {@code Creature.Beast};
     * {@code Enchantment.Primal} → {@code Enchantment.Primal}.
     */
    public static String toValidType(final String spec) {
        final String s = spec.trim();
        final int dot = s.indexOf('.');
        final String head = dot < 0 ? s : s.substring(0, dot);
        if (CardType.isACardType(head)) {
            return s;
        }
        if (CardType.isASubType(head)) {
            return "Creature." + s;
        }
        // unknown head (custom/typo): leave it alone, let the engine's valid-card
        // matching decide — a bad string simply never matches instead of crashing.
        return s;
    }

    /**
     * Readable form of the type for reminder text, e.g. {@code Creature.Beast} → {@code Beast},
     * {@code Creature} → {@code creature}, {@code Enchantment.Primal} → {@code Primal enchantment}.
     */
    public static String toDescType(final String spec) {
        final String s = spec.trim();
        final int dot = s.indexOf('.');
        if (dot < 0) {
            if (CardType.isACardType(s)) {
                return s.toLowerCase();
            }
            if (CardType.isASubType(s)) {
                return s;
            }
            return s.toLowerCase();
        }
        final String head = s.substring(0, dot);
        final String tail = s.substring(dot + 1);
        if (head.equalsIgnoreCase("Creature")) {
            return tail;
        }
        if (head.equalsIgnoreCase("Card")) {
            return tail;
        }
        return tail + " " + head.toLowerCase();
    }

    /** Renders "Beast Specialty 2" for the given raw type spec and amount. */
    public static String getTitle(final String typeSpec, final int amount) {
        return toDescType(typeSpec) + " Specialty " + amount;
    }

    /** Renders "Beast Specialty 2 (As long as you control 2 or more Beast permanents, ...)". */
    public static String getTitleWithReminder(final String typeSpec, final int amount) {
        final String reminder = Keyword.SPECIALTY.getReminderText()
                .replace("%d", String.valueOf(amount))
                .replace("%s", toDescType(typeSpec));
        return getTitle(typeSpec, amount) + " (" + reminder + ")";
    }

    /** How many permanents of {@code typeSpec} the given player controls on the battlefield. */
    public static int countPermanents(final Player player, final String typeSpec) {
        return CardLists.count(player.getCardsIn(ZoneType.Battlefield),
                CardPredicates.restriction(toControlledValid(typeSpec).split(","), player, null, null));
    }

    /**
     * Builds the valid-card restriction for "permanents of this type you control".
     * <p>
     * <b>Engine pitfall (R48):</b> Forge's valid-card parser splits the <i>type part</i> on the
     * first {@code '.'} and treats everything after the first {@code '+'} as card properties.
     * That makes {@code <A>.<B>.YouCtrl} silently match nothing (the controller property lands
     * in the type part), so a multi component type must use {@code +} instead:
     * <ul>
     *   <li>{@code Creature} → {@code Creature.YouCtrl}</li>
     *   <li>{@code Creature.Primal} → {@code Creature.Primal+YouCtrl}</li>
     *   <li>{@code Card.Harvin} → {@code Card.Harvin+YouCtrl}</li>
     * </ul>
     */
    public static String toControlledValid(final String typeSpec) {
        final String valid = toValidType(typeSpec).trim();
        if (valid.indexOf('+') >= 0) {
            return valid; // the author supplied properties already
        }
        return valid + (valid.indexOf('.') < 0 ? "." : "+") + "YouCtrl";
    }

    /**
     * Evaluates a {@code Specialty$ <type>:<count>} condition string for the given player.
     *
     * @return {@code true} when the player controls at least {@code <count>} permanents of the type.
     */
    public static boolean meetsCondition(final Player player, final String condition) {
        if (player == null || condition == null || condition.trim().isEmpty()) {
            return false;
        }
        final int sep = condition.lastIndexOf(':');
        if (sep < 0) {
            return false;
        }
        final String typeSpec = condition.substring(0, sep).trim();
        final int needed;
        try {
            needed = Integer.parseInt(condition.substring(sep + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return countPermanents(player, typeSpec) >= needed;
    }
}
