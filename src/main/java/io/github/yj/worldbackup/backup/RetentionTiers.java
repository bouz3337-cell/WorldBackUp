package io.github.yj.worldbackup.backup;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 계단식 보관. 최근일수록 촘촘히, 오래될수록 성기게 남긴다.
 *
 * <p>백업 주기를 여러 개로 나누는 대신 <b>하나의 주기로 뜨고 오래된 것을 솎아낸다.</b>
 * 결과는 같은데 백업 횟수가 늘지 않는다. 백업 한 번은 청크 저장을 기다리느라 서버를
 * 잠깐 멈추게 하므로, 주기를 나누면 여러 주기가 겹치는 시점에 그 멈춤이 몰린다.</p>
 *
 * <p>테러를 자는 동안 당하고 아침에 발견하는 상황이 이 구조가 필요한 이유다. 발견이 늦어도
 * 사고 직전으로 돌아갈 수 있으려면 <b>지난 하루의 해상도</b>가 살아 있어야 한다.
 * 예전 {@code keep-daily} 는 "그 날의 가장 최근 백업"을 남겼는데, 새벽에 당하면 그 하나에
 * 이미 피해가 들어 있어 아무 쓸모가 없었다.</p>
 */
public final class RetentionTiers {

    /**
     * @param every 이 계단의 간격. {@link Duration#ZERO} 면 시간과 무관하게 최신 것부터 그대로 남긴다.
     * @param keep  이 계단에서 남길 개수(= 구간 수)
     */
    public record Tier(Duration every, int keep) {
    }

    private RetentionTiers() {
    }

    /**
     * 계단을 따라 남길 백업을 고른다.
     *
     * <p>최신 쪽부터 계단을 하나씩 소비하며 시간 축을 거슬러 올라간다. 각 구간에서 대표 하나만
     * 남기고, 어떤 계단에도 들지 못한 백업은 삭제 후보가 된다.</p>
     *
     * <p>손상된 백업은 대표가 될 수 없다. 복원에 쓸 수 없는 것을 그 구간의 유일한 백업으로
     * 남기면 그 시간대가 통째로 비는 것과 같다.</p>
     *
     * @param newestFirst 최신순 백업 목록
     * @param now         기준 시각
     * @return 남겨야 할 백업의 id
     */
    public static Set<String> select(List<BackupEntry> newestFirst, List<Tier> tiers, Instant now) {
        Set<String> keep = new LinkedHashSet<>();
        if (tiers.isEmpty()) return keep;

        List<BackupEntry> candidates = newestFirst.stream().filter(BackupEntry::complete).toList();
        int index = 0;
        Instant cursor = now;

        for (Tier tier : tiers) {
            if (tier.keep() <= 0) continue;

            // 간격이 0 인 계단은 "최근 N개는 무조건" 이라는 뜻이다.
            if (tier.every().isZero()) {
                for (int i = 0; i < tier.keep() && index < candidates.size(); i++, index++) {
                    BackupEntry entry = candidates.get(index);
                    keep.add(entry.id());
                    cursor = entry.createdAt();
                }
                continue;
            }

            for (int bucket = 0; bucket < tier.keep(); bucket++) {
                Instant end = cursor;
                Instant start = cursor.minus(tier.every());
                BackupEntry chosen = null;

                while (index < candidates.size()) {
                    BackupEntry entry = candidates.get(index);
                    if (!entry.createdAt().isBefore(end)) {
                        index++; // 앞 계단이 이미 지나온 구간
                        continue;
                    }
                    if (entry.createdAt().isBefore(start)) break; // 다음 구간의 몫
                    if (chosen == null || preferred(entry, chosen)) chosen = entry;
                    index++;
                }

                if (chosen != null) keep.add(chosen.id());
                cursor = start;
            }
        }
        return keep;
    }

    /**
     * 같은 구간에서 누구를 대표로 삼을지.
     *
     * <p>순회가 최신순이라 기본값은 그 구간의 가장 최근 백업이다. 다만 차등 백업이 대표가 되면
     * 그 기준이 되는 전체 백업까지 함께 붙잡아야 해서, 오래된 계단일수록 디스크가 두 배로 든다.
     * 그래서 같은 구간에 전체 백업이 있으면 그쪽을 고른다.</p>
     */
    private static boolean preferred(BackupEntry candidate, BackupEntry current) {
        return !candidate.isDifferential() && current.isDifferential();
    }
}
