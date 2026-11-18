package com.clinic.mpi.service.ambiguity;

import com.clinic.mpi.domain.AmbiguityRun;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * 可行身份划分引擎（纯计算，无持久化）。
 *
 * <p>约束模型：人工确认的同一人关系（当前图谱主体）= 必须连接，已拒绝候选 = 禁止连接，
 * 待定候选 = 可选连接；来源身份不可重复归属（划分性质）。与证据相容的身份划分 =
 * 从确认主体出发、仅沿可选连接合并、且任一划分块不含禁止连接对的集合划分；
 * 等价地：每个划分块在可选连接图上诱导连通子图，且不跨越禁止连接。</p>
 *
 * <p>两条计算路线：ENUMERATE 对规模受控分量枚举全部合法划分（受限增长串 + 禁止连接剪枝，
 * 叶子处校验块连通性），结果精确；OPTIMIZE / 超限分量走构造式搜索（定向构造 + 随机局部搜索）
 * 给出可达到端点与见证划分，并用松弛界标注是否已证实（未证实 = 保守，不宣称精确；
 * 预算为 0 连构造都未执行 = 未完成）。</p>
 *
 * <p>比率指标（复诊率、复查覆盖）的分子分母始终在同一合法划分上联合计算：
 * 全局最优经 Dinkelbach 参数法在各分量可达到 (分子,分母) 选项上联合求得，
 * 不把某指标的最小值与另一指标的最大值拼成虚假比率。</p>
 */
public final class PartitionEngine {

    private PartitionEngine() {
    }

    // ---------- 输入模型 ----------

    /** 一次就诊（预约）：site 为院区（来源系统后缀），time 为就诊时间。 */
    public record Visit(String site, Instant time) {
    }

    /** 一次检验：testName + 检验时间。 */
    public record Lab(String testName, Instant time) {
    }

    /** 分析主体：确认主体（超节点）或基线分组；聚合其下全部来源身份的就诊与检验。 */
    public static final class Subject {
        public final List<String> sources = new ArrayList<>();      // "REG_A/P-A-001"
        public final List<UUID> identityIds = new ArrayList<>();
        public final List<Visit> visits = new ArrayList<>();
        public final List<Lab> labs = new ArrayList<>();

        public boolean hasVisits() {
            return !visits.isEmpty();
        }

        public boolean alreadyCross() {
            return visits.stream().map(Visit::site).distinct().count() >= 2;
        }
    }

    /** 分量内边（主体下标对）。 */
    public record Edge(int a, int b, String label) {
    }

    /** 运行配置。 */
    public record Config(AmbiguityRun.Route route, int maxComponentSuperNodes,
                         long maxPartitionsPerComponent, long maxMillis, long windowDays) {
        /** OPTIMIZE 路线下仍走精确枚举的分量规模上限 */
        static final int OPTIMIZE_ENUMERATE_BELOW = 8;

        boolean shouldEnumerate(int superNodes) {
            if (route == AmbiguityRun.Route.OPTIMIZE) {
                return superNodes <= OPTIMIZE_ENUMERATE_BELOW;
            }
            return superNodes <= maxComponentSuperNodes;
        }
    }

    // ---------- 指标向量 ----------

    /** 一个划分在一次运行口径下的联合指标贡献（分子分母同源）。 */
    public record Vec(long patients, long cross, long pairs, long covered) {
    }

    /** 评估一组主体在指定划分（assignment[i]=块号）下的指标贡献。 */
    public static Vec evaluate(List<Subject> subjects, int[] assignment, int numBlocks, long windowDays) {
        long patients = 0, cross = 0, pairs = 0, covered = 0;
        @SuppressWarnings("unchecked")
        List<Subject>[] blocks = new List[numBlocks];
        for (int i = 0; i < subjects.size(); i++) {
            int b = assignment[i];
            if (blocks[b] == null) {
                blocks[b] = new ArrayList<>();
            }
            blocks[b].add(subjects.get(i));
        }
        for (List<Subject> block : blocks) {
            if (block == null) {
                continue;
            }
            Map<String, Integer> sites = new HashMap<>();
            Map<String, List<Instant>> labsByName = new HashMap<>();
            for (Subject s : block) {
                for (Visit v : s.visits) {
                    sites.merge(v.site(), 1, Integer::sum);
                }
                for (Lab l : s.labs) {
                    labsByName.computeIfAbsent(l.testName(), k -> new ArrayList<>()).add(l.time());
                }
            }
            long visitCount = sites.values().stream().mapToLong(Integer::longValue).sum();
            if (visitCount > 0) {
                patients++;
                if (sites.size() >= 2) {
                    cross++;
                }
            }
            for (List<Instant> times : labsByName.values()) {
                times.sort(Instant::compareTo);
                for (int i = 1; i < times.size(); i++) {
                    pairs++;
                    long gapDays = Duration.between(times.get(i - 1), times.get(i)).toDays();
                    if (gapDays <= windowDays) {
                        covered++;
                    }
                }
            }
        }
        return new Vec(patients, cross, pairs, covered);
    }

    // ---------- 分量求解 ----------

    /** 一个可达到选项：指标向量 + 见证划分（分量内 assignment）。 */
    public record Option(Vec vec, int[] assignment) {
    }

    public static final class ComponentResult {
        public boolean enumerated;      // 合法划分已完全枚举
        public boolean truncated;       // 枚举被资源上限截断
        public boolean incomplete;      // 资源限制下连构造式搜索都未执行
        public long partitionsExplored; // 探索的划分叶子/移动总数（含不可行）
        public long feasibleCount;      // 发现的合法划分数（枚举路径为精确值）
        public final List<Option> options = new ArrayList<>();  // 可达到（按向量去重）
        // 已证实松弛界
        public long denLB, denUB, crossLB, crossUB, pairsLB, pairsUB, covLB, covUB;

        public boolean hasOptions() {
            return !options.isEmpty();
        }
    }

    /** 求解一个歧义连通分量。 */
    public static ComponentResult solveComponent(List<Subject> subjects, List<Edge> optional,
                                                 List<Edge> cannot, Config cfg, long deadlineNanos) {
        ComponentResult result = new ComponentResult();
        int k = subjects.size();
        boolean[][] optionalAdj = adjacency(k, optional);
        boolean[][] cannotAdj = adjacency(k, cannot);

        if (optional.isEmpty()) {
            // 无可选连接：唯一合法划分 = 全部独立（禁止连接只确认分离），直接精确求解
            int[] separate = new int[k];
            for (int i = 0; i < k; i++) {
                separate[i] = i;
            }
            Vec vec = evaluate(subjects, separate, k, cfg.windowDays());
            result.options.add(new Option(vec, separate));
            result.partitionsExplored = 1;
            result.feasibleCount = 1;
            result.enumerated = true;
            deriveProvenFromOptions(result);
            return result;
        }

        if (cfg.shouldEnumerate(k)) {
            enumerate(subjects, optionalAdj, cannotAdj, cfg, result);
            if (!result.truncated) {
                result.enumerated = true;
                deriveProvenFromOptions(result);
                return result;
            }
            // 截断：已探索划分保留为可达到选项，再补构造式搜索与松弛界
        }
        constructive(subjects, optionalAdj, cannotAdj, cfg, result, deadlineNanos);
        if (!result.hasOptions()) {
            result.incomplete = true;
            return result;
        }
        deriveRelaxation(subjects, cannotAdj, result);
        return result;
    }

    private static boolean[][] adjacency(int k, List<Edge> edges) {
        boolean[][] adj = new boolean[k][k];
        for (Edge e : edges) {
            adj[e.a()][e.b()] = true;
            adj[e.b()][e.a()] = true;
        }
        return adj;
    }

    // ---------- 路线一：精确枚举（受限增长串 + 禁止连接剪枝 + 叶子连通性校验） ----------

    private static void enumerate(List<Subject> subjects, boolean[][] optionalAdj,
                                  boolean[][] cannotAdj, Config cfg, ComponentResult result) {
        int k = subjects.size();
        int[] assign = new int[k];
        Arrays.fill(assign, -1);
        Map<Vec, int[]> found = new LinkedHashMap<>();
        long[] explored = {0};
        long[] feasible = {0};
        boolean[] truncated = {false};
        enumerateRec(subjects, optionalAdj, cannotAdj, cfg, found, explored, feasible, truncated,
                assign, 0, 0);
        result.partitionsExplored += explored[0];
        result.feasibleCount += feasible[0];
        result.truncated = truncated[0];
        found.forEach((vec, assignment) -> result.options.add(new Option(vec, assignment)));
    }

    private static void enumerateRec(List<Subject> subjects, boolean[][] optionalAdj,
                                     boolean[][] cannotAdj, Config cfg,
                                     Map<Vec, int[]> found, long[] explored, long[] feasible,
                                     boolean[] truncated,
                                     int[] assign, int i, int numBlocks) {
        if (truncated[0]) {
            return;
        }
        int k = subjects.size();
        if (i == k) {
            explored[0]++;
            if (explored[0] > cfg.maxPartitionsPerComponent()) {
                truncated[0] = true;
                return;
            }
            if (blocksConnected(assign, numBlocks, optionalAdj)) {
                feasible[0]++;
                Vec vec = evaluate(subjects, assign, numBlocks, cfg.windowDays());
                found.putIfAbsent(vec, assign.clone());
            }
            return;
        }
        for (int b = 0; b < numBlocks; b++) {
            if (!cannotConflict(assign, i, b, cannotAdj)) {
                assign[i] = b;
                enumerateRec(subjects, optionalAdj, cannotAdj, cfg, found, explored, feasible,
                        truncated, assign, i + 1, numBlocks);
            }
        }
        assign[i] = numBlocks;
        enumerateRec(subjects, optionalAdj, cannotAdj, cfg, found, explored, feasible,
                truncated, assign, i + 1, numBlocks + 1);
        assign[i] = -1;
    }

    private static boolean cannotConflict(int[] assign, int i, int b, boolean[][] cannotAdj) {
        for (int j = 0; j < assign.length; j++) {
            if (assign[j] == b && cannotAdj[i][j]) {
                return true;
            }
        }
        return false;
    }

    /** 校验每个划分块在可选连接图上诱导连通子图（防止无证据的传递合并）。 */
    private static boolean blocksConnected(int[] assign, int numBlocks, boolean[][] optionalAdj) {
        for (int b = 0; b < numBlocks; b++) {
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < assign.length; i++) {
                if (assign[i] == b) {
                    members.add(i);
                }
            }
            if (!sideConnected(members, optionalAdj)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sideConnected(List<Integer> members, boolean[][] optionalAdj) {
        if (members.size() <= 1) {
            return true;
        }
        BitSet seen = new BitSet();
        List<Integer> stack = new ArrayList<>();
        seen.set(members.get(0));
        stack.add(members.get(0));
        while (!stack.isEmpty()) {
            int cur = stack.remove(stack.size() - 1);
            for (int m : members) {
                if (!seen.get(m) && optionalAdj[cur][m]) {
                    seen.set(m);
                    stack.add(m);
                }
            }
        }
        for (int m : members) {
            if (!seen.get(m)) {
                return false;
            }
        }
        return true;
    }

    private static void deriveProvenFromOptions(ComponentResult result) {
        result.denLB = Long.MAX_VALUE;
        result.crossLB = Long.MAX_VALUE;
        result.pairsLB = Long.MAX_VALUE;
        result.covLB = Long.MAX_VALUE;
        for (Option o : result.options) {
            result.denLB = Math.min(result.denLB, o.vec().patients());
            result.denUB = Math.max(result.denUB, o.vec().patients());
            result.crossLB = Math.min(result.crossLB, o.vec().cross());
            result.crossUB = Math.max(result.crossUB, o.vec().cross());
            result.pairsLB = Math.min(result.pairsLB, o.vec().pairs());
            result.pairsUB = Math.max(result.pairsUB, o.vec().pairs());
            result.covLB = Math.min(result.covLB, o.vec().covered());
            result.covUB = Math.max(result.covUB, o.vec().covered());
        }
    }

    // ---------- 路线二：构造式搜索 + 松弛界（大规模分量；端点可达到但可能未证实） ----------

    private static void constructive(List<Subject> subjects, boolean[][] optionalAdj,
                                     boolean[][] cannotAdj, Config cfg, ComponentResult result,
                                     long deadlineNanos) {
        int k = subjects.size();
        if (System.nanoTime() >= deadlineNanos) {
            return; // 预算为 0：连定向构造也不执行 → 未完成
        }
        Map<Vec, int[]> found = new LinkedHashMap<>();
        java.util.Set<String> seenAssignments = new java.util.HashSet<>();
        for (Option o : result.options) {
            found.putIfAbsent(o.vec(), o.assignment());
            seenAssignments.add(Arrays.toString(o.assignment()));
        }
        long seededCount = seenAssignments.size();
        // 定向构造 1：全部独立（最大独立就诊人数 / 最小跨院分子）
        int[] separate = new int[k];
        for (int i = 0; i < k; i++) {
            separate[i] = i;
        }
        recordOption(subjects, cfg, found, seenAssignments, separate);
        // 定向构造 2：贪心合并（最小独立就诊人数）
        recordOption(subjects, cfg, found, seenAssignments, greedyMerge(k, optionalAdj, cannotAdj, null));
        // 定向构造 3：跨院最大化（异院区两两配对）
        recordOption(subjects, cfg, found, seenAssignments,
                crossMaximizer(subjects, k, optionalAdj, cannotAdj));
        // 随机局部搜索：随机合并/拆分，收集更多可达到向量
        Random rng = new Random(20261118L);
        long explored = 0;
        while (System.nanoTime() < deadlineNanos) {
            int[] cur = greedyMerge(k, optionalAdj, cannotAdj, rng);
            for (int step = 0; step < 64 && System.nanoTime() < deadlineNanos; step++) {
                recordOption(subjects, cfg, found, seenAssignments, cur);
                explored++;
                int[] next = (rng.nextBoolean() ? randomMerge(cur, optionalAdj, cannotAdj, rng)
                        : randomSplit(cur, optionalAdj, rng));
                if (next != null) {
                    cur = next;
                }
            }
        }
        result.partitionsExplored += explored;
        // 构造阶段新发现的合法划分（截断枚举已计入 feasibleCount 的不重复统计）
        result.feasibleCount += seenAssignments.size() - seededCount;
        result.options.clear();
        found.forEach((vec, assignment) -> result.options.add(new Option(vec, assignment)));
    }

    private static void recordOption(List<Subject> subjects, Config cfg,
                                     Map<Vec, int[]> found, java.util.Set<String> seenAssignments,
                                     int[] assignment) {
        int[] normalized = normalize(assignment);
        int numBlocks = Arrays.stream(normalized).max().orElse(-1) + 1;
        Vec vec = evaluate(subjects, normalized, numBlocks, cfg.windowDays());
        found.putIfAbsent(vec, normalized);
        seenAssignments.add(Arrays.toString(normalized));
    }

    private static int[] normalize(int[] assignment) {
        Map<Integer, Integer> remap = new HashMap<>();
        int[] normalized = new int[assignment.length];
        int next = 0;
        for (int i = 0; i < assignment.length; i++) {
            Integer mapped = remap.get(assignment[i]);
            if (mapped == null) {
                mapped = next++;
                remap.put(assignment[i], mapped);
            }
            normalized[i] = mapped;
        }
        return normalized;
    }

    /** 贪心合并：沿可选连接做并查集，合并前校验两块之间无禁止连接。 */
    private static int[] greedyMerge(int k, boolean[][] optionalAdj, boolean[][] cannotAdj, Random rng) {
        int[] parent = new int[k];
        for (int i = 0; i < k; i++) {
            parent[i] = i;
        }
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                if (optionalAdj[i][j]) {
                    edges.add(new int[]{i, j});
                }
            }
        }
        if (rng != null) {
            for (int i = edges.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int[] tmp = edges.get(i);
                edges.set(i, edges.get(j));
                edges.set(j, tmp);
            }
        }
        for (int[] e : edges) {
            int ra = find(parent, e[0]), rb = find(parent, e[1]);
            if (ra == rb || blocksCannotConflict(ra, rb, parent, cannotAdj)) {
                continue;
            }
            parent[ra] = rb;
        }
        return parent;
    }

    private static boolean blocksCannotConflict(int ra, int rb, int[] parent, boolean[][] cannotAdj) {
        for (int i = 0; i < parent.length; i++) {
            if (find(parent, i) != ra) {
                continue;
            }
            for (int j = 0; j < parent.length; j++) {
                if (find(parent, j) == rb && cannotAdj[i][j]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int find(int[] parent, int x) {
        int r = x;
        while (parent[r] != r) {
            r = parent[r];
        }
        int cur = x;
        while (parent[cur] != r) {
            int next = parent[cur];
            parent[cur] = r;
            cur = next;
        }
        return r;
    }

    /** 跨院最大化：反复合并"各自单院区且院区不同"的两块，制造跨院块。 */
    private static int[] crossMaximizer(List<Subject> subjects, int k,
                                        boolean[][] optionalAdj, boolean[][] cannotAdj) {
        int[] parent = new int[k];
        for (int i = 0; i < k; i++) {
            parent[i] = i;
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < k && !progress; i++) {
                for (int j = i + 1; j < k && !progress; j++) {
                    int ri = find(parent, i), rj = find(parent, j);
                    if (ri == rj || !optionalAdj[i][j]) {
                        continue;
                    }
                    String si = singleSiteOf(subjects, parent, ri);
                    String sj = singleSiteOf(subjects, parent, rj);
                    if (si != null && sj != null && !si.equals(sj)
                            && !blocksCannotConflict(ri, rj, parent, cannotAdj)) {
                        parent[ri] = rj;
                        progress = true;
                    }
                }
            }
        }
        return parent;
    }

    /** 块内全部就诊是否只有一个院区；无就诊或已跨院返回 null。 */
    private static String singleSiteOf(List<Subject> subjects, int[] parent, int root) {
        String site = null;
        for (int i = 0; i < parent.length; i++) {
            if (find(parent, i) != root) {
                continue;
            }
            for (Visit v : subjects.get(i).visits) {
                if (site == null) {
                    site = v.site();
                } else if (!site.equals(v.site())) {
                    return null;
                }
            }
        }
        return site;
    }

    private static int[] randomMerge(int[] cur, boolean[][] optionalAdj, boolean[][] cannotAdj,
                                     Random rng) {
        int k = cur.length;
        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                if (optionalAdj[i][j] && cur[i] != cur[j]) {
                    candidates.add(new int[]{i, j});
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int[] pick = candidates.get(rng.nextInt(candidates.size()));
        int from = cur[pick[0]], to = cur[pick[1]];
        for (int i = 0; i < k; i++) {
            if (cur[i] != from) {
                continue;
            }
            for (int j = 0; j < k; j++) {
                if (cur[j] == to && cannotAdj[i][j]) {
                    return null;
                }
            }
        }
        int[] next = cur.clone();
        for (int i = 0; i < k; i++) {
            if (next[i] == from) {
                next[i] = to;
            }
        }
        return next;
    }

    private static int[] randomSplit(int[] cur, boolean[][] optionalAdj, Random rng) {
        int k = cur.length;
        Map<Integer, Integer> sizes = new HashMap<>();
        for (int b : cur) {
            sizes.merge(b, 1, Integer::sum);
        }
        List<Integer> splittable = sizes.entrySet().stream()
                .filter(e -> e.getValue() >= 2).map(Map.Entry::getKey).toList();
        if (splittable.isEmpty()) {
            return null;
        }
        int target = splittable.get(rng.nextInt(splittable.size()));
        List<Integer> members = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            if (cur[i] == target) {
                members.add(i);
            }
        }
        // 随机连通子集作为新块：从随机种子出发按可选边生长
        int seed = members.get(rng.nextInt(members.size()));
        int newBlock = Arrays.stream(cur).max().orElse(0) + 1;
        int[] next = cur.clone();
        next[seed] = newBlock;
        List<Integer> frontier = new ArrayList<>();
        frontier.add(seed);
        while (!frontier.isEmpty() && rng.nextBoolean()) {
            int curNode = frontier.remove(rng.nextInt(frontier.size()));
            for (int m : members) {
                if (next[m] == target && optionalAdj[curNode][m]) {
                    next[m] = newBlock;
                    frontier.add(m);
                }
            }
        }
        List<Integer> sideA = new ArrayList<>(), sideB = new ArrayList<>();
        for (int m : members) {
            (next[m] == target ? sideA : sideB).add(m);
        }
        if (!sideConnected(sideA, optionalAdj) || !sideConnected(sideB, optionalAdj)) {
            return null;
        }
        return next;
    }

    /** 松弛界：构造路径下已证实的分量级上下界。 */
    private static void deriveRelaxation(List<Subject> subjects, boolean[][] cannotAdj,
                                         ComponentResult result) {
        long active = subjects.stream().filter(Subject::hasVisits).count();
        long alreadyCross = subjects.stream().filter(Subject::alreadyCross).count();
        result.denUB = active;
        result.denLB = active == 0 ? 0 : Math.max(1, greedyClique(subjects, cannotAdj));
        result.crossLB = alreadyCross;
        result.crossUB = alreadyCross + (active - alreadyCross) / 2;
        Map<String, Long> byName = new HashMap<>();
        for (Subject s : subjects) {
            for (Lab l : s.labs) {
                byName.merge(l.testName(), 1L, Long::sum);
            }
        }
        long pairsUb = byName.values().stream().mapToLong(c -> Math.max(0, c - 1)).sum();
        result.pairsUB = pairsUb;
        result.pairsLB = 0;
        result.covUB = pairsUb;
        result.covLB = 0;
    }

    /** 禁止连接图上的贪心团：团内主体两两互斥，必须分属不同块 → 最少块数下界。 */
    private static long greedyClique(List<Subject> subjects, boolean[][] cannotAdj) {
        int k = subjects.size();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            if (subjects.get(i).hasVisits()) {
                order.add(i);
            }
        }
        order.sort((a, b) -> Integer.compare(degree(b, cannotAdj), degree(a, cannotAdj)));
        List<Integer> clique = new ArrayList<>();
        for (int v : order) {
            boolean ok = true;
            for (int u : clique) {
                if (!cannotAdj[v][u]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                clique.add(v);
            }
        }
        return clique.size();
    }

    private static int degree(int v, boolean[][] adj) {
        int d = 0;
        for (boolean b : adj[v]) {
            if (b) {
                d++;
            }
        }
        return d;
    }

    // ---------- 全局合并：分子分母联合（Dinkelbach 参数法） ----------

    /** 单指标双侧界结果。 */
    public static final class Bound {
        public Double minValue;
        public boolean minExact;
        public int[] minWitness;      // 各分量所选选项下标（与 components 对齐）
        public Double provenMin;
        public Long minNum;
        public Long minDen;
        public Double maxValue;
        public boolean maxExact;
        public int[] maxWitness;
        public Double provenMax;
        public Long maxNum;
        public Long maxDen;

        public boolean anyIncomplete() {
            return minValue == null && maxValue == null && provenMin == null && provenMax == null;
        }
    }

    /** 分量级 (num,den) 投影与松弛界的绑定。 */
    public record MetricLens(ToLongFunction<Vec> num, ToLongFunction<Vec> den,
                             ToLongFunction<ComponentResult> numLB, ToLongFunction<ComponentResult> numUB,
                             ToLongFunction<ComponentResult> denLB, ToLongFunction<ComponentResult> denUB) {
        public static final MetricLens CROSS_RATE = new MetricLens(Vec::cross, Vec::patients,
                c -> c.crossLB, c -> c.crossUB, c -> c.denLB, c -> c.denUB);
        public static final MetricLens LAB_COVERAGE = new MetricLens(Vec::covered, Vec::pairs,
                c -> c.covLB, c -> c.covUB, c -> c.pairsLB, c -> c.pairsUB);
    }

    /**
     * 比率指标全局合并：在各分量可达到 (num,den) 选项上联合优化 Σnum/Σden。
     * exact 判定：全枚举分量天然精确；未全枚举分量需通过支配检查
     * （其松弛界不可能提供更优选项），否则端点标为未证实（保守）。
     */
    public static Bound combineRatio(List<ComponentResult> components, Vec fixed, MetricLens lens) {
        Bound bound = new Bound();
        if (components.stream().anyMatch(c -> !c.hasOptions())) {
            return bound; // 有未完成分量 → 指标界未知
        }
        long fixedNum = lens.num().applyAsLong(fixed);
        long fixedDen = lens.den().applyAsLong(fixed);

        // 分母恒 0 → 指标未定义
        long maxDen = fixedDen;
        for (ComponentResult c : components) {
            maxDen += c.options.stream().mapToLong(o -> lens.den().applyAsLong(o.vec())).max().orElse(0);
        }
        if (maxDen == 0) {
            bound.minExact = true;
            bound.maxExact = true;
            return bound;
        }

        // 已证实松弛界（端点未证实时给出，不宣称精确）
        long sumNumLB = fixedNum, sumNumUB = fixedNum, sumDenLB = fixedDen, sumDenUB = fixedDen;
        for (ComponentResult c : components) {
            sumNumLB += lens.numLB().applyAsLong(c);
            sumNumUB += lens.numUB().applyAsLong(c);
            sumDenLB += lens.denLB().applyAsLong(c);
            sumDenUB += lens.denUB().applyAsLong(c);
        }
        bound.provenMin = sumDenUB == 0 ? null : clamp01((double) sumNumLB / sumDenUB);
        bound.provenMax = sumDenLB == 0 ? 1.0 : clamp01((double) sumNumUB / sumDenLB);

        // 下界（min Σnum/Σden）
        SideResult minSide = dinkelbach(components, fixedNum, fixedDen, lens, true);
        bound.minValue = minSide.value;
        bound.minWitness = minSide.witness;
        bound.minNum = minSide.num;
        bound.minDen = minSide.den;
        bound.minExact = minSide.converged && dominanceHolds(components, lens, minSide, true);
        // 上界
        SideResult maxSide = dinkelbach(components, fixedNum, fixedDen, lens, false);
        bound.maxValue = maxSide.value;
        bound.maxWitness = maxSide.witness;
        bound.maxNum = maxSide.num;
        bound.maxDen = maxSide.den;
        bound.maxExact = maxSide.converged && dominanceHolds(components, lens, maxSide, false);
        return bound;
    }

    private static final class SideResult {
        Double value;
        long num;
        long den;
        int[] witness;
        boolean converged;
    }

    /** Dinkelbach 参数法：在分量选项笛卡尔积上优化 Σnum/Σden（分子分母同源）。 */
    private static SideResult dinkelbach(List<ComponentResult> components, long fixedNum,
                                         long fixedDen, MetricLens lens, boolean minimize) {
        SideResult result = new SideResult();
        double r = initialRatio(components, fixedNum, fixedDen, lens, minimize);
        SideResult best = null;
        for (int iter = 0; iter < 80; iter++) {
            SideResult cur = pick(components, fixedNum, fixedDen, lens, r, minimize, -1);
            if (cur == null) {
                break;
            }
            if (cur.den == 0) {
                // 存在分母为 0 的合法划分：界仅在分母>0 的划分上定义
                cur = pickWithPositiveDen(components, fixedNum, fixedDen, lens, r, minimize);
                if (cur == null) {
                    return result; // 全部分划分母恒 0（调用方已拦截，防御）
                }
            }
            double rNew = (double) cur.num / cur.den;
            if (best != null && Math.abs(rNew - r) < 1e-12) {
                cur.value = rNew;
                cur.converged = true;
                return cur;
            }
            r = rNew;
            best = cur;
        }
        if (best != null) {
            best.value = (double) best.num / best.den;
        }
        return best == null ? result : best;
    }

    private static double initialRatio(List<ComponentResult> components, long fixedNum,
                                       long fixedDen, MetricLens lens, boolean minimize) {
        long num = fixedNum, den = fixedDen;
        for (ComponentResult c : components) {
            num += lens.num().applyAsLong(c.options.get(0).vec());
            den += lens.den().applyAsLong(c.options.get(0).vec());
        }
        return den == 0 ? (minimize ? 0.0 : 1.0) : (double) num / den;
    }

    /** 按当前比率 r 为每个分量挑选最优选项（minimize: n - r·d 最小）。 */
    private static SideResult pick(List<ComponentResult> components, long fixedNum, long fixedDen,
                                   MetricLens lens, double r, boolean minimize, int forcedComponent) {
        SideResult out = new SideResult();
        out.num = fixedNum;
        out.den = fixedDen;
        out.witness = new int[components.size()];
        for (int i = 0; i < components.size(); i++) {
            ComponentResult c = components.get(i);
            int bestIdx = -1;
            double bestKey = minimize ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            for (int oi = 0; oi < c.options.size(); oi++) {
                Vec v = c.options.get(oi).vec();
                long n = lens.num().applyAsLong(v);
                long d = lens.den().applyAsLong(v);
                if (forcedComponent == i && d == 0) {
                    continue;
                }
                double key = n - r * d;
                if (minimize ? key < bestKey - 1e-12 : key > bestKey + 1e-12) {
                    bestKey = key;
                    bestIdx = oi;
                }
            }
            if (bestIdx < 0) {
                return null;
            }
            out.witness[i] = bestIdx;
            out.num += lens.num().applyAsLong(c.options.get(bestIdx).vec());
            out.den += lens.den().applyAsLong(c.options.get(bestIdx).vec());
        }
        return out;
    }

    /** 分母可为 0 时的兜底：强制一个分量取正分母选项，逐分量尝试取最优。 */
    private static SideResult pickWithPositiveDen(List<ComponentResult> components, long fixedNum,
                                                  long fixedDen, MetricLens lens, double r,
                                                  boolean minimize) {
        SideResult best = null;
        for (int forced = 0; forced < components.size(); forced++) {
            int f = forced;
            boolean hasPositive = components.get(f).options.stream()
                    .anyMatch(o -> lens.den().applyAsLong(o.vec()) > 0);
            if (!hasPositive) {
                continue;
            }
            SideResult cur = pick(components, fixedNum, fixedDen, lens, r, minimize, f);
            if (cur == null || cur.den == 0) {
                continue;
            }
            double value = (double) cur.num / cur.den;
            cur.value = value;
            if (best == null || (minimize ? value < best.value - 1e-12 : value > best.value + 1e-12)) {
                best = cur;
            }
        }
        return best;
    }

    /** 支配检查：未全枚举分量的松弛界不可能给出比已选更优的选项时，端点才算证实。 */
    private static boolean dominanceHolds(List<ComponentResult> components, MetricLens lens,
                                          SideResult side, boolean minimize) {
        if (side.value == null || side.witness == null) {
            return false;
        }
        double r = side.value;
        for (int i = 0; i < components.size(); i++) {
            ComponentResult c = components.get(i);
            if (c.enumerated || c.options.size() <= 1) {
                continue;
            }
            Vec chosen = c.options.get(side.witness[i]).vec();
            double chosenKey = lens.num().applyAsLong(chosen) - r * lens.den().applyAsLong(chosen);
            if (minimize) {
                // 任何未见选项 key ≥ numLB - r·denUB；若不劣于已选则支配成立
                double worst = lens.numLB().applyAsLong(c) - r * lens.denUB().applyAsLong(c);
                if (worst < chosenKey - 1e-9) {
                    return false;
                }
            } else {
                // num ≤ den 恒成立（跨院人数 ≤ 就诊人数、覆盖对数 ≤ 复查对数）：
                // 未见选项 key = n - r·d 在 d ∈ {denLB, denUB, clamp(numUB,·)} 处取得上界
                double numUB = lens.numUB().applyAsLong(c);
                long denLB = lens.denLB().applyAsLong(c);
                long denUB = lens.denUB().applyAsLong(c);
                double worst = Double.NEGATIVE_INFINITY;
                long dMid = Math.min(denUB, Math.max(denLB, (long) numUB));
                for (long d : new long[]{denLB, denUB, dMid}) {
                    worst = Math.max(worst, Math.min(numUB, d) - r * d);
                }
                if (worst > chosenKey + 1e-9) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        return Math.min(1.0, Math.max(0.0, v));
    }

    /** 独立就诊人数（可加指标）全局合并。 */
    public static Bound combineSum(List<ComponentResult> components, Vec fixed) {
        Bound bound = new Bound();
        if (components.stream().anyMatch(c -> !c.hasOptions())) {
            return bound; // 有未完成分量 → 指标界未知
        }
        long min = fixed.patients(), max = fixed.patients();
        long provenMin = fixed.patients(), provenMax = fixed.patients();
        boolean minExact = true;
        int[] minWitness = new int[components.size()];
        int[] maxWitness = new int[components.size()];
        for (int i = 0; i < components.size(); i++) {
            ComponentResult c = components.get(i);
            long compMin = Long.MAX_VALUE, compMax = 0;
            int minIdx = 0, maxIdx = 0;
            for (int oi = 0; oi < c.options.size(); oi++) {
                Vec v = c.options.get(oi).vec();
                if (v.patients() < compMin) {
                    compMin = v.patients();
                    minIdx = oi;
                }
                if (v.patients() > compMax) {
                    compMax = v.patients();
                    maxIdx = oi;
                }
            }
            min += compMin;
            max += compMax;
            provenMin += c.denLB;
            provenMax += c.denUB;
            if (!c.enumerated && compMin != c.denLB) {
                minExact = false;
            }
            minWitness[i] = minIdx;
            maxWitness[i] = maxIdx;
        }
        bound.minValue = (double) min;
        bound.minExact = minExact;
        bound.minWitness = minWitness;
        bound.provenMin = (double) provenMin;
        bound.maxValue = (double) max;
        bound.maxExact = provenMax == max;
        bound.maxWitness = maxWitness;
        bound.provenMax = (double) provenMax;
        return bound;
    }
}
