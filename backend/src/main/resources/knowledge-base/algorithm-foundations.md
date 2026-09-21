# Algorithm foundations / 算法基础

Original bilingual study notes. Complexity claims state their assumptions; examples are explanatory, not benchmark results. / 原创双语学习笔记；复杂度结论附带适用前提，示例不是性能实测。

## Complexity and amortized cost / 复杂度与摊销分析

Define input size and the counted operation before giving a complexity bound. Big-O is an asymptotic upper bound, not a prediction in milliseconds. Worst-case analysis limits every permitted input of a size; expected analysis depends on a distribution or randomized choices. Amortized analysis bounds the total cost of a sequence of operations rather than the average over random inputs. A dynamic array may copy all existing elements during one growth operation, yet geometric growth makes a sequence of n appends O(n), or O(1) amortized per append. An individual append can still take O(n). Count auxiliary memory separately from storage of the input and output. Validate the proposed growth model with reproducible measurements over increasing sizes, while recording warm-up, allocation, and runtime conditions. A lower asymptotic bound does not guarantee a faster implementation on small inputs.

复杂度先说明输入规模、基本操作和分析条件。摊销分析约束整个操作序列的总成本，不等于随机输入上的平均复杂度。动态数组按倍数扩容时，n 次追加总成本为 O(n)，单次摊销 O(1)，但触发复制的某一次仍可能 O(n)。辅助空间应与输入、输出空间分开；实测还受缓存、分配、预热和运行环境影响。

Primary references / 一手参考：[Princeton: Analysis of Algorithms](https://algs4.cs.princeton.edu/14analysis/)

## Binary search boundaries / 二分查找边界

Binary search requires a sorted search range or another monotone predicate. To find the first index whose value is at least a target, keep a half-open interval [lo, hi). Values before lo are known to be smaller; a candidate answer is never discarded from [lo, hi]. Compute mid as lo + (hi - lo) / 2 using integer division. If a[mid] is smaller, set lo = mid + 1; otherwise set hi = mid. On termination lo is the insertion position and may equal n. Check lo < n and equality separately when the task asks for membership. Lower bound selects the first duplicate and upper bound selects the position after the last duplicate. Search uses O(log n) comparisons on a random-access array, but inserting into an array still takes O(n) movement in the worst case.

二分依赖有序区间或单调判定，不能直接用于任意无序数组。找第一个不小于目标的元素时维护左闭右开区间 [lo, hi)，小于目标则 lo = mid + 1，否则 hi = mid。结束位置可能为 n，判断是否存在还要检查边界与相等关系。lower bound 指向第一处重复值，upper bound 指向最后重复值之后。定位 O(log n) 不代表数组插入也是 O(log n)。

Primary references / 一手参考：[Python: bisect](https://docs.python.org/3/library/bisect.html)

## Hash table collisions and load / 哈希冲突与装载率

A hash table maps keys to a finite set of buckets, so different keys can collide. Equal keys must have equal hash codes; equal hash codes do not establish key equality. Resolve collisions with a technique such as separate chaining or open addressing, and compare keys using the defined equality relation. Under suitable hashing assumptions and controlled load, lookup and update have expected O(1) cost. That expectation is not an unconditional worst-case guarantee. Long collision chains, overloaded probe sequences, expensive equality checks, and adversarial input can change costs. Resizing reduces load but costs work and temporarily needs memory. Do not mutate equality-relevant key fields while a key is stored: a later lookup can inspect a different bucket. Choose an ordered search tree when the requirement includes sorted iteration, predecessor queries, or predictable logarithmic comparison bounds.

有限桶数量意味着哈希冲突不可避免：键相等要求哈希值相等，但哈希值相同不能证明键相等。链式地址和开放寻址都要处理冲突，并用相等规则确认键。合适哈希与受控装载率下，查询和更新期望 O(1)，并非无条件最坏 O(1)。扩容有复制成本；已入表的键不应修改影响哈希和相等性的字段。需要有序遍历或前驱查询时，应考虑有序树结构。

Primary references / 一手参考：[Princeton: Hash Tables](https://algs4.cs.princeton.edu/34hash/)

## Stacks queues and deques / 栈队列与双端队列

A stack removes the most recently added element: last in, first out. A queue removes the oldest remaining element: first in, first out. A deque supports insertion and removal at both ends, so it can implement either access discipline. Match the structure to the algorithm rather than choosing a container only by its name. Iterative depth-first traversal uses a stack; breadth-first traversal uses a queue. Python deque appends and pops at either end are approximately O(1), while removing index zero from a list requires O(n) element movement. Indexed access near the middle of a deque is O(n), so a deque is not a universal replacement for an array. Check empty-container behavior and whether a bounded deque silently discards from the opposite end. Efficient endpoint operations alone do not make a multi-step concurrent protocol atomic.

栈是后进先出，队列是先进先出，双端队列可从两端操作。DFS 常用栈，BFS 常用队列。Python deque 两端追加和弹出近似 O(1)，列表头部删除需要 O(n) 移动；deque 中间索引访问仍可能 O(n)。使用有界 deque 要注意满时可能丢弃另一端元素。容器单次操作的性质不能直接推导多个操作组成的并发协议安全。

Primary references / 一手参考：[Python: deque](https://docs.python.org/3/library/collections.html#collections.deque)

## Sorting stability and worst cases / 排序稳定性与最坏情况

A stable sort preserves the original relative order of records with equal sort keys. This matters when records were already ordered by a secondary attribute. Standard array mergesort repeatedly merges sorted subarrays, gives O(n log n) worst-case comparisons, and usually uses O(n) auxiliary storage. Preserve stability during merging by taking the left record first when keys compare equal. Classic quicksort partitions around a pivot; balanced partitions give O(n log n) work, but consistently extreme partitions can take O(n squared). Randomized pivots or a shuffle reduce the probability of poor partitions without changing the worst case of classic quicksort. These statements describe algorithm variants, not every library function named sort. Choose using stability, memory limits, comparison cost, input shape, and the actual library contract. Verify duplicates, already sorted input, reverse order, and empty input.

稳定排序保留相等键记录原有的相对顺序，适合已有次级排序的记录。常见数组归并排序最坏 O(n log n)，辅助空间通常 O(n)；归并遇相等键先取左侧可维持稳定。经典快速排序平均表现好，但持续极端分区会达到 O(n²)；随机化降低概率，不抹去经典实现的最坏情况。具体库的排序保证要查接口契约，不能仅凭算法名称推断。

Primary references / 一手参考：[Princeton: Mergesort](https://algs4.cs.princeton.edu/22mergesort/) · [Princeton: Quicksort](https://algs4.cs.princeton.edu/23quicksort/)

## Heaps and streaming top k / 堆与流式 Top K

A binary min-heap keeps every parent no larger than its children, so the root is the minimum. The whole array is not globally sorted. Heap insertion and root removal take O(log n), while reading the root takes O(1). Bottom-up heap construction takes O(n), which is better than inserting n values one by one. To retain the largest k values in a stream, maintain a min-heap of at most k candidates. Once full, discard a value no larger than the root; otherwise replace the root and restore the invariant. Processing n values costs O(n log k) for k greater than one and uses O(k) storage; final sorted output needs additional sorting. Define behavior for k = 0, k larger than the input, ties, and invalid inputs. Updating arbitrary priorities also needs bookkeeping or stale-entry handling.

最小堆只保证父节点不大于子节点，根为最小值，并非整个数组有序。插入和删除堆顶 O(log n)，读取堆顶 O(1)，自底向上建堆 O(n)。流式保留最大 k 项可维护大小最多 k 的最小堆，满后只有超过堆顶的新值才替换。k 大于 1 时总处理 O(n log k)，空间 O(k)；若最终要求排序，还需额外处理。必须明确 k = 0、重复值和超出输入规模等边界。

Primary references / 一手参考：[Python: heapq](https://docs.python.org/3/library/heapq.html)

## BFS shortest unweighted paths / BFS 无权最短路径

Breadth-first search explores vertices in increasing distance measured in number of edges from the start. Use a first-in, first-out queue and mark each vertex when it is enqueued, so multiple incoming edges do not repeatedly schedule it. Store a predecessor when first discovering a vertex to reconstruct one shortest path. With adjacency lists, the traversal takes O(V + E) time and O(V) auxiliary space over the reached graph. For disconnected input, an unreachable destination has no path; do not interpret an uninitialized distance as zero. Multi-source BFS begins with all sources at distance zero and finds distance to the closest source. The shortest-path guarantee applies to unweighted graphs or edges with the same cost. Arbitrary positive weights require a weighted shortest-path method, because the fewest edges need not have the lowest total cost.

BFS 用先进先出队列按边数逐层扩展，入队时标记访问，避免同一节点被重复安排。首次发现时记录前驱即可还原一条最短路径。邻接表遍历 O(V + E)，辅助空间 O(V)。未到达节点应明确表示不可达；多源 BFS 将所有起点距离设为零。该最短性适用于无权或等权边，任意正权图里边数最少不代表总成本最低。

Primary references / 一手参考：[Princeton: Undirected Graphs](https://algs4.cs.princeton.edu/41graph/)

## DFS cycle detection and topological order / DFS 判环与拓扑排序

Depth-first search follows one branch before returning to explore alternatives. In a directed graph, distinguish undiscovered vertices, vertices on the active recursion path, and finished vertices. An edge to an active-path vertex proves a directed cycle; an edge to any previously visited vertex does not. For an undirected graph the parent edge needs special treatment, so the directed rule cannot simply be copied. A directed acyclic graph has a topological order in which each edge goes from an earlier vertex to a later one. Reverse DFS finishing order yields such an order only after establishing that the graph is acyclic. Kahn's algorithm instead removes zero-indegree vertices; processing fewer than V vertices signals a cycle. Both approaches take O(V + E) with adjacency lists. Recursive implementations need stack-depth care on long chains.

有向 DFS 判环必须区分未访问、当前递归路径上、已完成三种状态；指向当前路径节点的边才是环证据，不能把所有已访问邻居都认作环。DAG 的拓扑序让每条边从前指向后；无环时 DFS 逆完成序可用。Kahn 算法不断移除入度零节点，若处理数小于 V，说明存在环。邻接表实现均为 O(V + E)，长链还需防递归栈溢出。

Primary references / 一手参考：[Princeton: Directed Graphs](https://algs4.cs.princeton.edu/42digraph/)

## Dijkstra and edge-weight assumptions / Dijkstra 与边权前提

Dijkstra's shortest-path algorithm repeatedly settles the unsettled vertex with the smallest tentative distance and relaxes its outgoing edges. Its usual correctness proof requires nonnegative edge weights: extending a path cannot make an already settled distance cheaper. A negative edge can invalidate that argument even without a negative cycle. Use a method that supports negative weights, such as Bellman-Ford, when that is part of the input contract. With a binary heap, adjacency lists, and appropriate priority updates, Dijkstra is commonly bounded by O((V + E) log V). An implementation that pushes updated distances without decrease-key must skip stale queue entries. Guard against numeric overflow and represent unreachable vertices explicitly. A shortest-path tree minimizes source-to-vertex path costs; it is different from a minimum spanning tree, which minimizes total connecting-edge weight.

Dijkstra 每次确定暂定距离最小的未确定节点，再松弛出边，通常要求边权非负。负边即使不形成负环，也可能破坏已确定距离不会再变小的证明。允许负边时应选 Bellman-Ford 等适配方法。二叉堆邻接表常见复杂度 O((V + E) log V)；用重复入堆代替 decrease-key 时需跳过过期项。还应处理溢出与不可达，且最短路径树不等于最小生成树。

Primary references / 一手参考：[Princeton: Shortest Paths](https://algs4.cs.princeton.edu/44sp/)

## Union-find connectivity / 并查集连通性

Disjoint-set union, also called union-find, maintains a partition of elements into disjoint components. Find returns a component representative; union merges two components. Two elements are connected exactly when their representatives match. Attach the smaller tree under the larger, or use ranks, to avoid tall chains. Path compression changes parent links during find so later lookups approach the root faster. Combining path compression with union by rank or size gives an amortized bound involving the inverse Ackermann function, which grows extremely slowly. This is not a promise that every individual operation has strict constant worst-case cost. Union-find is useful for incremental undirected connectivity and Kruskal's minimum-spanning-tree algorithm. It does not directly provide an actual connecting path, directed reachability, or efficient arbitrary edge deletion; those requirements need additional data structures or different algorithms.

并查集维护互不相交的连通分量，find 返回代表元，union 合并分量，代表元相同表示连通。按大小或秩合并避免高链，路径压缩让后续查找更接近根；组合后的摊销复杂度含增长极慢的反阿克曼函数，不是每次严格最坏 O(1)。它适合增量无向连通性和 Kruskal，但不能直接给出连接路径、解决有向可达性或高效支持任意删边。

Primary references / 一手参考：[Princeton: Union-Find](https://algs4.cs.princeton.edu/15uf/)

## Dynamic programming state design / 动态规划状态设计

Dynamic programming reuses solved subproblems when a recursive formulation revisits the same states. Define exactly what one state means, which smaller states determine it, the base cases, and the order of evaluation. Memoization computes reachable states on demand; tabulation evaluates a dependency-respecting order. For edit distance, a useful state is the minimum edits needed to transform one prefix into another, with transitions for insertion, deletion, and replacement. Time is approximately the number of reachable states multiplied by the transition work per state, not automatically polynomial just because a table is used. A state that omits relevant information can produce a fast but incorrect recurrence. Reducing memory to a few rows is valid only when older rows are no longer required; reconstructing an optimal solution may require backpointers or recomputation. Test the recurrence against exhaustive solutions on small inputs.

动态规划通过复用状态避免重复求解。先定义状态含义、转移依赖、边界和求值顺序；记忆化按需计算，自底向上按依赖顺序填表。编辑距离可用两个前缀间最少编辑次数作为状态。时间约为可达状态数乘每状态转移成本，并非只要用了表就一定多项式。状态漏掉必要信息会使递推错误；滚动数组省内存时，还需考虑最优方案还原。

Primary references / 一手参考：[MIT 6.006: Dynamic programming lecture notes](https://ocw.mit.edu/courses/6-006-introduction-to-algorithms-fall-2011/pages/lecture-notes/)

## Greedy choices and cut proofs / 贪心选择与割性质证明

A greedy algorithm commits to a locally preferred choice without exploring every future alternative. Local attractiveness alone does not prove a global optimum. Supply an exchange argument, a cut property, or another invariant that justifies each commitment. For a minimum spanning tree of a connected undirected weighted graph, a minimum-weight edge crossing a cut can be chosen safely when the cut respects the already selected forest. Kruskal considers edges by increasing weight and adds an edge only when it joins different components; union-find detects cycles efficiently. Equal weights may yield several valid optimal trees. On disconnected input the analogous result is a minimum spanning forest. This proof does not say that repeatedly taking the cheapest next edge solves shortest paths or arbitrary scheduling. First identify the exact optimization objective and constraints, then demonstrate why an optimal solution can include the proposed choice.

贪心的局部最优需要交换论证、割性质或其他不变式，不能凭直觉推出全局最优。最小生成树中，与已选森林相容的割上最轻边可以安全加入。Kruskal 按权重递增检查边，仅连接不同分量，可用并查集判环。相同权重可能存在多个最优树；非连通输入得到最小生成森林。该证明不意味着任意最短路或调度都能不断选眼前最便宜项。

Primary references / 一手参考：[Princeton: Minimum Spanning Trees](https://algs4.cs.princeton.edu/43mst/)

## Backtracking and safe pruning / 回溯与安全剪枝

Backtracking builds a candidate solution one decision at a time, explores a branch, then undoes its state changes before trying the next choice. A base case records a completed valid solution. Prune only when a partial assignment cannot lead to any permitted solution, or when a proven bound cannot beat the best result already found. For N-queens, place one queen per row and reject columns or diagonals already occupied; undo those occupied markers on return. Pruning often helps practical runtime but does not generally remove exponential worst-case search. Shared mutable state, forgotten undo operations, and storing a reference instead of a copy of a completed candidate are common correctness errors. If the task requests every solution, a heuristic that abandons promising branches may make the result incomplete. Count recursion depth and output size separately from the number of explored states.

回溯逐步选择、递归探索，再撤销状态后尝试下一分支；满足边界时记录完整合法解。只有部分状态已不可能完成，或有证明的界无法优于当前最优时才安全剪枝。N 皇后可逐行放置，拒绝已占列和对角线，返回时撤销标记。剪枝改善实际速度但通常不消除指数最坏情况；漏撤销、共享可变状态、保存候选引用而非副本都会导致错误。

Primary references / 一手参考：[Princeton: Recursion and backtracking](https://introcs.cs.princeton.edu/java/23recursion/)
