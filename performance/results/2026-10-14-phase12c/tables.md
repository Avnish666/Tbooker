# Phase 12C measured tables

Arithmetic means of per-run measurements; latency percentiles are not pooled. Counts are sums.

| Workload | JVMs | Runs | HTTP/s | p50 ms | p95 ms | p99 ms | Unexpected % | 409s | Drops | Target passes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| catalogue | 1 | 2 | 596.98 | 26.19 | 122.98 | 196.82 | 0.0000 | 0 | 171 | 0/2 |
| catalogue | 2 | 2 | 599.61 | 2.61 | 50.57 | 79.64 | 0.0000 | 0 | 21 | 0/2 |
| availability | 1 | 2 | 150.01 | 2.75 | 20.30 | 39.91 | 0.0000 | 0 | 0 | 2/2 |
| availability | 2 | 2 | 150.01 | 4.17 | 11.49 | 25.53 | 0.0000 | 0 | 0 | 2/2 |
| mixed | 1 | 2 | 111.56 | 254.56 | 1857.50 | 3080.40 | 0.0000 | 0 | 86 | 0/2 |
| mixed | 2 | 2 | 134.68 | 15.48 | 158.42 | 336.88 | 0.0000 | 0 | 0 | 2/2 |
| events | 1 | 2 | 60.63 | 1479.80 | 6471.99 | 9147.47 | 0.5808 | 0 | 406 | 0/2 |
| events | 2 | 2 | 89.52 | 103.72 | 549.65 | 861.47 | 0.0000 | 0 | 4 | 1/2 |
| contention | 1 | 2 | 127.90 | 677.51 | 1667.48 | 2132.81 | 0.0000 | 7757 | 0 | 2/2 |
| contention | 2 | 2 | 163.16 | 561.31 | 1172.78 | 1471.14 | 0.0000 | 9889 | 0 | 2/2 |


## Every tested operating point

| Job | Offered iter/s | Actual HTTP/s | p50 | p95 | p99 | Unexpected % | 409 % | Max sampled VUs | Drops | Pass |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| catalogue-r200-n1-s1 | 200 | 199.99 | 1.46 | 7.29 | 28.79 | 0.0000 | 0.00 | 2 | 0 | True |
| catalogue-r200-n2-s2 | 200 | 200.01 | 2.25 | 7.79 | 22.76 | 0.0000 | 0.00 | 4 | 0 | True |
| catalogue-r600-n1-s1 | 600 | 598.30 | 19.09 | 77.32 | 134.32 | 0.0000 | 0.00 | 47 | 50 | False |
| catalogue-r600-n2-s2 | 600 | 599.50 | 2.98 | 56.89 | 93.42 | 0.0000 | 0.00 | 41 | 13 | False |
| catalogue-r600-n2-s3 | 600 | 599.71 | 2.24 | 44.25 | 65.86 | 0.0000 | 0.00 | 34 | 8 | False |
| catalogue-r600-n1-s4 | 600 | 595.66 | 33.29 | 168.63 | 259.32 | 0.0000 | 0.00 | 127 | 121 | False |
| catalogue-r1200-n1-s1 | 1200 | 731.06 | 208.04 | 510.51 | 998.62 | 0.0000 | 0.00 | 200 | 13723 | False |
| catalogue-r1200-n2-s2 | 1200 | 570.69 | 80.12 | 1467.16 | 2417.11 | 0.0000 | 0.00 | 200 | 18635 | False |
| availability-r50-n1-s1 | 50 | 50.00 | 3.71 | 7.87 | 11.42 | 0.0000 | 0.00 | 1 | 0 | True |
| availability-r50-n2-s2 | 50 | 50.00 | 5.43 | 10.36 | 14.96 | 0.0000 | 0.00 | 1 | 0 | True |
| availability-r150-n1-s1 | 150 | 150.00 | 2.92 | 26.04 | 43.63 | 0.0000 | 0.00 | 1 | 0 | True |
| availability-r150-n2-s2 | 150 | 150.01 | 4.04 | 11.49 | 25.57 | 0.0000 | 0.00 | 2 | 0 | True |
| availability-r150-n2-s3 | 150 | 150.00 | 4.30 | 11.50 | 25.50 | 0.0000 | 0.00 | 2 | 0 | True |
| availability-r150-n1-s4 | 150 | 150.02 | 2.59 | 14.56 | 36.20 | 0.0000 | 0.00 | 1 | 0 | True |
| availability-r400-n1-s1 | 400 | 393.62 | 119.38 | 580.73 | 891.25 | 0.0000 | 0.00 | 183 | 172 | False |
| availability-r400-n2-s2 | 400 | 399.96 | 11.31 | 72.99 | 109.96 | 0.0000 | 0.00 | 32 | 1 | False |
| mixed-r5-n1-s1 | 5 | 45.23 | 4.58 | 52.66 | 97.52 | 0.0000 | 0.00 | 2 | 0 | True |
| mixed-r5-n2-s2 | 5 | 45.18 | 6.27 | 34.33 | 54.25 | 0.0000 | 0.00 | 0 | 0 | True |
| mixed-r15-n1-s1 | 15 | 114.34 | 222.34 | 1707.18 | 2878.11 | 0.0000 | 0.00 | 76 | 38 | False |
| mixed-r15-n2-s2 | 15 | 134.47 | 16.40 | 171.06 | 371.58 | 0.0000 | 0.00 | 14 | 0 | True |
| mixed-r15-n2-s3 | 15 | 134.89 | 14.55 | 145.77 | 302.19 | 0.0000 | 0.00 | 11 | 0 | True |
| mixed-r15-n1-s4 | 15 | 108.79 | 286.78 | 2007.83 | 3282.69 | 0.0000 | 0.00 | 87 | 48 | False |
| mixed-r40-n1-s1 | 40 | 104.85 | 394.61 | 6301.06 | 10000.44 | 1.5521 | 0.00 | 200 | 742 | False |
| mixed-r40-n2-s2 | 40 | 221.30 | 251.95 | 2764.90 | 5055.96 | 0.0267 | 0.00 | 200 | 369 | False |
| events-r10-n1-s1 | 10 | 30.00 | 14.05 | 107.68 | 196.63 | 0.0000 | 0.00 | 3 | 0 | True |
| events-r10-n2-s2 | 10 | 30.00 | 13.83 | 46.36 | 99.12 | 0.0000 | 0.00 | 1 | 0 | True |
| events-r30-n1-s1 | 30 | 62.33 | 1496.02 | 6141.30 | 8486.02 | 0.3311 | 0.00 | 200 | 193 | False |
| events-r30-n2-s2 | 30 | 89.72 | 68.47 | 308.33 | 518.22 | 0.0000 | 0.00 | 21 | 0 | True |
| events-r30-n2-s3 | 30 | 89.32 | 138.98 | 790.98 | 1204.72 | 0.0000 | 0.00 | 41 | 4 | False |
| events-r30-n1-s4 | 30 | 58.92 | 1463.58 | 6802.68 | 9808.92 | 0.8305 | 0.00 | 200 | 213 | False |
| events-r60-n1-s1 | 60 | 62.32 | 2107.31 | 7870.20 | 10000.31 | 1.6018 | 0.00 | 200 | 1061 | False |
| events-r60-n2-s2 | 60 | 107.50 | 1019.67 | 4737.28 | 7466.65 | 0.2229 | 0.00 | 200 | 602 | False |
| contention-n1-s1 | 100 VUs | 137.57 | 617.16 | 1519.38 | 2010.03 | 0.0000 | 99.98 | 100 | 0 | True |
| contention-n2-s2 | 100 VUs | 159.46 | 577.11 | 1261.73 | 1601.26 | 0.0000 | 99.98 | 100 | 0 | True |
| contention-n2-s3 | 100 VUs | 166.87 | 545.50 | 1083.83 | 1341.02 | 0.0000 | 99.98 | 100 | 0 | True |
| contention-n1-s4 | 100 VUs | 118.23 | 737.85 | 1815.57 | 2255.59 | 0.0000 | 99.97 | 100 | 0 | True |
| recovery-n1 | 10 | 30.06 | 14.08 | 104.78 | 191.69 | 0.0000 | 0.00 | 2 | 0 | True |
| recovery-n2 | 10 | 30.05 | 15.03 | 48.65 | 124.15 | 0.0000 | 0.00 | 1 | 0 | True |


## Cache, SQL, and sampled resources

| Job | SQL calls | SQL/HTTP | Cache hit % | App CPU % | PG CPU % | k6 CPU % | Heap MiB | Pool active/pending | DB locks |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| catalogue-r200-n1-s1 | 122 | 0.020 | 99.700 | 60.08 | 0.07 | 19.26 | 73.59 | 1/0 | 0 |
| catalogue-r200-n2-s2 | 197 | 0.033 | 99.700 | 73.88 | 1.24 | 17.54 | 160.25 | 0/0 | 0 |
| catalogue-r600-n1-s1 | 133 | 0.007 | 99.894 | 90.97 | 2.01 | 37.36 | 88.69 | 0/0 | 0 |
| catalogue-r600-n2-s2 | 256 | 0.014 | 99.794 | 147.04 | 2.12 | 44.05 | 147.00 | 0/0 | 0 |
| catalogue-r600-n2-s3 | 214 | 0.012 | 99.883 | 150.75 | 0.98 | 38.82 | 152.43 | 0/0 | 0 |
| catalogue-r600-n1-s4 | 133 | 0.007 | 99.905 | 102.92 | 1.54 | 42.39 | 95.90 | 0/0 | 0 |
| catalogue-r1200-n1-s1 | 1683 | 0.076 | 99.923 | 89.70 | 2.12 | 48.36 | 99.65 | 11/9 | 0 |
| catalogue-r1200-n2-s2 | 5518 | 0.318 | 99.899 | 179.19 | 5.11 | 43.84 | 184.39 | 20/10 | 0 |
| availability-r50-n1-s1 | 4589 | 3.059 | N/A | 22.90 | 4.01 | 8.80 | 71.08 | 1/0 | 0 |
| availability-r50-n2-s2 | 4663 | 3.109 | N/A | 39.91 | 4.36 | 7.90 | 152.57 | 0/0 | 0 |
| availability-r150-n1-s1 | 13589 | 3.020 | N/A | 61.79 | 7.29 | 15.24 | 81.83 | 1/0 | 0 |
| availability-r150-n2-s2 | 13666 | 3.036 | N/A | 87.65 | 8.54 | 16.00 | 155.12 | 2/0 | 0 |
| availability-r150-n2-s3 | 13658 | 3.035 | N/A | 90.64 | 8.52 | 16.24 | 161.96 | 2/0 | 0 |
| availability-r150-n1-s4 | 13587 | 3.019 | N/A | 56.19 | 8.39 | 17.68 | 73.93 | 1/0 | 0 |
| availability-r400-n1-s1 | 35623 | 3.012 | N/A | 91.28 | 20.21 | 32.55 | 100.57 | 20/88 | 0 |
| availability-r400-n2-s2 | 36211 | 3.018 | N/A | 188.46 | 22.64 | 38.38 | 143.22 | 10/0 | 0 |
| mixed-r5-n1-s1 | 12750 | 9.382 | 88.907 | 64.00 | 5.58 | 5.58 | 83.57 | 1/0 | 0 |
| mixed-r5-n2-s2 | 12826 | 9.438 | 88.907 | 77.40 | 7.38 | 6.56 | 143.41 | 1/0 | 0 |
| mixed-r15-n1-s1 | 34574 | 9.302 | 95.932 | 98.84 | 19.10 | 12.31 | 72.48 | 20/38 | 0 |
| mixed-r15-n2-s2 | 37747 | 9.300 | 96.231 | 188.55 | 19.36 | 12.58 | 152.49 | 9/0 | 0 |
| mixed-r15-n2-s3 | 37735 | 9.297 | 96.286 | 187.71 | 18.97 | 12.88 | 147.02 | 8/0 | 0 |
| mixed-r15-n1-s4 | 33547 | 9.249 | 95.593 | 101.07 | 15.51 | 10.75 | 89.93 | 20/46 | 0 |
| mixed-r40-n1-s1 | 35478 | 8.741 | 96.337 | 100.19 | 17.52 | 14.61 | 126.05 | 20/176 | 0 |
| mixed-r40-n2-s2 | 69529 | 9.299 | 97.983 | 205.65 | 41.17 | 15.68 | 182.14 | 38/123 | 0 |
| events-r10-n1-s1 | 23184 | 25.760 | N/A | 76.88 | 9.07 | 4.88 | 83.69 | 3/0 | 0 |
| events-r10-n2-s2 | 23261 | 25.846 | N/A | 103.73 | 10.82 | 5.65 | 162.53 | 0/0 | 0 |
| events-r30-n1-s1 | 49610 | 23.467 | N/A | 100.28 | 25.02 | 7.10 | 128.18 | 20/181 | 0 |
| events-r30-n2-s2 | 69523 | 25.749 | N/A | 177.68 | 31.48 | 9.79 | 151.69 | 20/0 | 0 |
| events-r30-n2-s3 | 69233 | 25.756 | N/A | 178.17 | 32.84 | 7.82 | 153.27 | 22/1 | 0 |
| events-r30-n1-s4 | 48705 | 23.793 | N/A | 101.54 | 26.01 | 9.98 | 102.39 | 20/182 | 0 |
| events-r60-n1-s1 | 51188 | 23.427 | N/A | 102.35 | 25.09 | 7.20 | 110.33 | 20/176 | 0 |
| events-r60-n2-s2 | 87621 | 24.414 | N/A | 200.50 | 46.21 | 10.43 | 175.99 | 40/153 | 0 |
| contention-n1-s1 | 37695 | 9.027 | N/A | 102.57 | 14.06 | 13.42 | 91.24 | 20/81 | 19 |
| contention-n2-s2 | 43773 | 9.048 | N/A | 198.14 | 17.81 | 14.20 | 169.90 | 40/60 | 39 |
| contention-n2-s3 | 45736 | 9.051 | N/A | 178.34 | 17.07 | 16.60 | 179.96 | 40/64 | 39 |
| contention-n1-s4 | 32357 | 9.031 | N/A | 100.97 | 13.12 | 12.74 | 98.73 | 20/80 | 19 |
| recovery-n1 | 23426 | 25.942 | N/A | 82.07 | 9.93 | 5.19 | 77.72 | 2/0 | 0 |
| recovery-n2 | 23594 | 26.128 | N/A | 92.23 | 10.14 | 5.15 | 166.87 | 1/0 | 0 |


## Event publication and projections

| Job | Events | Published/s observed | Publish p95 ms | Notification p95 ms | Analytics p95 ms | Peak outbox | End outbox | Peak missing markers | Drain s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mixed-r5-n1-s1 | 151 | 4.89 | 983.03 | 986.03 | 983.70 | 5 | 0 | 10 | 0.09 |
| mixed-r5-n2-s2 | 151 | 4.87 | 597.98 | 605.98 | 609.23 | 3 | 0 | 6 | 0.09 |
| mixed-r15-n1-s1 | 413 | 11.36 | 2612.06 | 20547.53 | 20352.88 | 33 | 24 | 578 | 0.09 |
| mixed-r15-n2-s2 | 451 | 14.00 | 859.13 | 906.80 | 905.69 | 9 | 0 | 18 | 0.09 |
| mixed-r15-n2-s3 | 451 | 14.21 | 986.35 | 1002.75 | 1009.85 | 21 | 0 | 42 | 0.09 |
| mixed-r15-n1-s4 | 403 | 10.09 | 5105.19 | 23797.68 | 22042.00 | 87 | 40 | 580 | 0.10 |
| mixed-r40-n1-s1 | 458 | 4.69 | 18195.44 | 32320.40 | 31549.40 | 270 | 270 | 747 | 9.99 |
| mixed-r40-n2-s2 | 831 | 20.22 | 6289.18 | 20848.40 | 25193.44 | 188 | 71 | 1144 | 0.09 |
| events-r10-n1-s1 | 300 | 9.61 | 979.01 | 993.91 | 993.13 | 3 | 0 | 6 | 0.09 |
| events-r10-n2-s2 | 300 | 9.60 | 947.73 | 950.71 | 954.63 | 7 | 1 | 14 | 0.09 |
| events-r30-n1-s1 | 707 | 5.64 | 25217.20 | 29024.20 | 28916.99 | 517 | 506 | 1293 | 21.00 |
| events-r30-n2-s2 | 900 | 26.85 | 679.99 | 812.43 | 779.31 | 19 | 0 | 38 | 0.10 |
| events-r30-n2-s3 | 896 | 27.36 | 966.99 | 3786.58 | 1523.09 | 28 | 0 | 120 | 0.09 |
| events-r30-n1-s4 | 688 | 6.19 | 23500.43 | 30974.30 | 30914.60 | 465 | 465 | 1233 | 19.26 |
| events-r60-n1-s1 | 740 | 4.30 | 29783.89 | 30512.83 | 30502.84 | 611 | 583 | 1396 | 26.08 |
| events-r60-n2-s2 | 1199 | 17.51 | 15728.94 | 28605.34 | 26492.77 | 586 | 586 | 1657 | 10.00 |
| recovery-n1 | 301 | 9.71 | 9591.56 | 10122.42 | 10172.54 | 101 | 0 | 202 | 0.11 |
| recovery-n2 | 301 | 9.55 | 8715.51 | 8739.57 | 8733.91 | 99 | 0 | 198 | 0.08 |


## JVM and infrastructure measurements

Memory is the maximum sampled sum across active JVMs. GC values are counter deltas over the observation window. CPU 100% represents one logical core.

| Job | App RAM MiB | GC pauses | GC pause s | DB connections | Redis CPU % | Kafka CPU % | NGINX CPU % | k6 RAM MiB | Consumer committed lag |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| catalogue-r200-n1-s1 | 374.30 | 22 | 0.112 | 3 | 1.86 | 9.09 | 3.95 | 42.69 | N/A |
| catalogue-r200-n2-s2 | 727.40 | 22 | 0.178 | 10 | 1.85 | 22.74 | 4.80 | 41.96 | N/A |
| catalogue-r600-n1-s1 | 388.90 | 69 | 0.438 | 4 | 2.37 | 22.80 | 9.82 | 61.62 | N/A |
| catalogue-r600-n2-s2 | 763.10 | 67 | 0.500 | 17 | 3.24 | 11.95 | 12.09 | 50.05 | N/A |
| catalogue-r600-n2-s3 | 770.00 | 67 | 0.458 | 14 | 3.43 | 25.12 | 10.35 | 49.04 | N/A |
| catalogue-r600-n1-s4 | 407.10 | 76 | 0.553 | 5 | 2.57 | 24.56 | 12.27 | 80.23 | N/A |
| catalogue-r1200-n1-s1 | 427.30 | 98 | 2.259 | 20 | 1.84 | 14.39 | 11.09 | 106.00 | N/A |
| catalogue-r1200-n2-s2 | 826.00 | 70 | 1.735 | 26 | 2.82 | 2.40 | 11.82 | 110.00 | N/A |
| availability-r50-n1-s1 | 361.50 | 24 | 0.100 | 7 | 0.87 | 5.01 | 1.92 | 48.83 | N/A |
| availability-r50-n2-s2 | 695.00 | 26 | 0.162 | 13 | 0.78 | 20.70 | 1.73 | 43.41 | N/A |
| availability-r150-n1-s1 | 384.40 | 71 | 0.243 | 8 | 0.67 | 21.18 | 3.77 | 41.57 | N/A |
| availability-r150-n2-s2 | 727.00 | 73 | 0.366 | 15 | 0.68 | 16.40 | 3.44 | 40.94 | N/A |
| availability-r150-n2-s3 | 726.70 | 73 | 0.369 | 14 | 0.76 | 2.07 | 3.76 | 41.76 | N/A |
| availability-r150-n1-s4 | 371.70 | 71 | 0.255 | 7 | 0.73 | 1.54 | 3.55 | 41.68 | N/A |
| availability-r400-n1-s1 | 434.50 | 205 | 1.805 | 20 | 0.90 | 28.75 | 7.16 | 98.31 | N/A |
| availability-r400-n2-s2 | 739.20 | 192 | 1.375 | 27 | 1.05 | 27.55 | 8.67 | 45.34 | N/A |
| mixed-r5-n1-s1 | 389.20 | 14 | 0.265 | 10 | 1.13 | 14.48 | 1.31 | 40.50 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| mixed-r5-n2-s2 | 787.20 | 15 | 0.526 | 19 | 0.89 | 22.99 | 1.54 | 49.65 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| mixed-r15-n1-s1 | 403.60 | 38 | 0.889 | 20 | 0.91 | 17.09 | 2.81 | 55.95 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| mixed-r15-n2-s2 | 788.10 | 42 | 1.228 | 22 | 1.55 | 25.23 | 3.34 | 42.02 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| mixed-r15-n2-s3 | 812.80 | 41 | 0.880 | 18 | 1.17 | 27.66 | 3.96 | 43.32 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| mixed-r15-n1-s4 | 410.20 | 38 | 1.177 | 20 | 0.59 | 20.89 | 3.01 | 59.65 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| mixed-r40-n1-s1 | 464.70 | 38 | 1.768 | 20 | 1.69 | 16.12 | 2.62 | 102.60 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| mixed-r40-n2-s2 | 843.10 | 81 | 2.951 | 40 | 1.48 | 44.46 | 5.36 | 103.40 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r10-n1-s1 | 393.10 | 11 | 0.274 | 10 | 0.81 | 5.61 | 1.37 | 45.22 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r10-n2-s2 | 788.50 | 13 | 0.453 | 18 | 0.95 | 24.05 | 1.31 | 35.94 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r30-n1-s1 | 459.50 | 28 | 1.104 | 20 | 0.61 | 20.28 | 1.84 | 84.63 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r30-n2-s2 | 803.90 | 37 | 0.901 | 29 | 0.35 | 15.67 | 3.06 | 40.57 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r30-n2-s3 | 795.20 | 37 | 1.076 | 39 | 0.71 | 26.28 | 2.74 | 46.47 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r30-n1-s4 | 467.70 | 27 | 0.958 | 20 | 0.34 | 15.16 | 2.17 | 80.23 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r60-n1-s1 | 465.50 | 26 | 0.875 | 20 | 0.36 | 33.32 | 2.59 | 95.00 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| events-r60-n2-s2 | 830.70 | 51 | 1.913 | 40 | 0.81 | 6.84 | 2.92 | 101.60 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| contention-n1-s1 | 428.70 | 24 | 0.829 | 20 | 0.75 | 26.46 | 3.63 | 65.11 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| contention-n2-s2 | 809.00 | 32 | 1.039 | 40 | 0.79 | 31.29 | 4.88 | 64.47 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| contention-n2-s3 | 816.20 | 32 | 0.795 | 40 | 0.99 | 7.52 | 4.46 | 64.82 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| contention-n1-s4 | 419.60 | 22 | 1.030 | 20 | 0.86 | 12.70 | 3.04 | 63.93 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| recovery-n1 | 393.10 | 12 | 0.276 | 11 | 0.85 | 13.94 | 1.22 | 37.62 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |
| recovery-n2 | 776.90 | 14 | 0.470 | 19 | 0.74 | 11.77 | 1.30 | 36.07 | {'tbooker-notifications-v1': 0, 'tbooker-analytics-v1': 0} |


## One versus two JVMs at the middle offered rate

A fixed offered rate caps measured throughput even when spare capacity exists. These ratios do not establish linear or maximum-capacity scaling.

| Workload | RPS change % | p95 reduction % | RPS ratio | Ratio / doubled app CPU budget |
| --- | --- | --- | --- | --- |
| catalogue | 0.44 | 58.88 | 1.004 | 0.502 |
| availability | -0.00 | 43.38 | 1.000 | 0.500 |
| mixed | 20.72 | 91.47 | 1.207 | 0.604 |
| events | 47.66 | 91.51 | 1.477 | 0.738 |
| contention | 27.57 | 29.67 | 1.276 | 0.638 |


## Tested capacity points

| Workload | JVMs | Highest rate passing every repetition (iter/s) | Failed tested rates (iter/s) | Highest offered rate passed |
| --- | --- | --- | --- | --- |
| catalogue | 1 | 200 | [600, 1200] | False |
| catalogue | 2 | 200 | [600, 1200] | False |
| availability | 1 | 150 | [400] | False |
| availability | 2 | 150 | [400] | False |
| mixed | 1 | 5 | [15, 40] | False |
| mixed | 2 | 15 | [40] | False |
| events | 1 | 10 | [30, 60] | False |
| events | 2 | 10 | [30, 60] | False |


A passing 30-second operating point is not a long-term sustainable maximum.


## Route latency within write workloads

Means of per-run route p95 values at the middle rate. These are separate from the all-HTTP percentile and include client-observed HTTP time only.

| Workload | JVMs | Catalogue p95 ms | Availability p95 ms | Hold p95 ms | Payment p95 ms | Confirmation p95 ms |
| --- | --- | --- | --- | --- | --- | --- |
| mixed | 1 | 393.58 | 1082.86 | 1295.89 | 3409.97 | 1159.77 |
| mixed | 2 | 79.78 | 80.37 | 115.87 | 396.97 | 99.60 |
| events | 1 | N/A | N/A | 3651.34 | 8394.57 | 3128.22 |
| events | 2 | N/A | N/A | 271.07 | 757.19 | 215.14 |
