package forge.game.card;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordView;

public record CounterKeywordType(KeywordView keyword) implements CounterType {

    // Rule 122.1b
    // NOTE (GBF DIY mod / Card-Forge-GBF): "Prowess" is appended on purpose.
    // 灵技指示物 (a prowess counter) is a GBF DIY design keyword counter; the official
    // list stops at Vigilance. The engine path is generic --
    // Card.createCounterStatic() emits "AddKeyword$ <counter name>" for every
    // isKeywordCounter() type -- so listing Prowess here makes the counter actually
    // grant prowess. Registered in docs/forge-src-patches.md as P-16.
    static ImmutableList<String> keywordCounter = ImmutableList.of(
            "Flying", "First Strike", "Double Strike", "Deathtouch", "Decayed", "Exalted", "Haste", "Hexproof",
            "Indestructible", "Lifelink", "Menace", "Reach", "Shadow", "Trample", "Vigilance", "Prowess");
    private static Map<String, CounterKeywordType> sMap = Maps.newHashMap();

    public static CounterKeywordType get(String s) {
        if (!sMap.containsKey(s)) {
            sMap.put(s, new CounterKeywordType(Keyword.getInstance(s).getView()));
        }
        return sMap.get(s);
    }

    public static Set<CounterType> getValues() {
        // add fixed first
        Set<CounterType> result = keywordCounter.stream().map(CounterKeywordType::get).collect(Collectors.toCollection(LinkedHashSet::new));
        // add variable ones later
        result.addAll(sMap.values());
        return result;
    }
    
    @Override
    public String toString() {
        return keyword.original();
    }

    public String getName() {
        return keyword.title();
    }

    @Override
    public String getCounterOnCardDisplayName() {
        return keyword.title();
    }

    @Override
    public boolean isKeywordCounter() {
        return true;
    }

    public static boolean isKeywordCounter(String keyword) {
        if (keyword.startsWith("Hexproof:")) {
            return true;
        }
        if (keyword.startsWith("Trample:")) {
            return true;
        }
        return keywordCounter.contains(keyword);
    }

    @Override
    public CounterAiCategory getAiCategory() {
        if (Keyword.DECAYED.equals(keyword.keyword())) {
            return CounterAiCategory.Negative;
        }
        return CounterAiCategory.Positive;
    }
}
