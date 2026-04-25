package com.executionagent.repetition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects repetition patterns in the last 6 commands.
 * Mirrors Python repetition.py patterns:
 *   - AAAAAA (period-1)
 *   - ABABAB (period-2)
 *   - ABCABC (period-3)
 *   - AAABBB (two-group)
 */
public class RepetitionDetector {

    private static final Logger LOG = LoggerFactory.getLogger(RepetitionDetector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Check if the candidate command would create a repetition pattern
     * when combined with the last 5 commands from history.
     *
     * @param lastCmds List of previous commands (most recent last), each is a Map with "name" and "args"
     * @param candidate The new command being proposed
     * @return true if a repetition pattern is detected
     */
    public static boolean isRepetition(List<Map<String, Object>> lastCmds, Map<String, Object> candidate) {
        // Build window of 6: last 5 + candidate
        int histSize = Math.min(lastCmds.size(), 5);
        List<Map<String, Object>> window = new ArrayList<>();
        if (histSize > 0) {
            window.addAll(lastCmds.subList(lastCmds.size() - histSize, lastCmds.size()));
        }
        window.add(candidate);

        if (window.size() < 6) return false;

        // Canonicalize each command to a stable JSON string
        List<String> s = new ArrayList<>();
        for (Map<String, Object> cmd : window) {
            s.add(canonicalize(cmd));
        }

        // Period-1: AAAAAA
        if (new HashSet<>(s).size() == 1) {
            LOG.info("Repetition detected: period-1 (AAAAAA)");
            return true;
        }

        // Period-2: ABABAB
        if (s.get(0).equals(s.get(2)) && s.get(2).equals(s.get(4))
                && s.get(1).equals(s.get(3)) && s.get(3).equals(s.get(5))
                && !s.get(0).equals(s.get(1))) {
            LOG.info("Repetition detected: period-2 (ABABAB)");
            return true;
        }

        // Period-3: ABCABC
        if (s.get(0).equals(s.get(3)) && s.get(1).equals(s.get(4)) && s.get(2).equals(s.get(5))) {
            Set<String> distinct = new HashSet<>(List.of(s.get(0), s.get(1), s.get(2)));
            if (distinct.size() == 3) {
                LOG.info("Repetition detected: period-3 (ABCABC)");
                return true;
            }
        }

        // Two-group: AAABBB
        if (s.get(0).equals(s.get(1)) && s.get(1).equals(s.get(2))
                && s.get(3).equals(s.get(4)) && s.get(4).equals(s.get(5))
                && !s.get(0).equals(s.get(3))) {
            LOG.info("Repetition detected: two-group (AAABBB)");
            return true;
        }

        return false;
    }

    private static String canonicalize(Map<String, Object> cmd) {
        try {
            // Sort keys for stable serialization
            Map<String, Object> sorted = new TreeMap<>(cmd);
            return MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            return cmd.toString();
        }
    }
}
