# Gemma 문맥 완성 한국어 수동 검토 — 2026-09-12

이 문서는 root가 두 실행의 원문과 후보를 직접 읽고 확정한 수동 판정을 옮긴 것이다. 사람 사용자 평가는 아니며, 채택률이나 사용자의 의도 적중률을 나타내지 않는다.

의미 통과는 앞뒤 구절과의 개연성에 대한 수동 판정일 뿐, 실사용자 의도 적중이나 사실 검증이 아니다. 일반적인 상투 응답이 많으므로 이 결과를 품질 완료나 출시 허가로 해석하면 안 된다.

후보 수와 한국어 품질은 분리했다. 후보 없음은 한국어 각 차원에서 실패가 아니라 평가 불가이며, terminal boundary는 후보 분모와 품질 평가에서 제외한다.

## 증거 원천과 실행 상태

- **기존 독립 공개 입력**: `outputs/gemma-context-20260912/emulator-tests/existing-full-03/summary.json`; fixture `independent_public_20260910`; SHA-256 `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; sample_finished 24개, fixture 집계 일치 `True`, 로그 끝 `OK (1 test)` 1회, `FAILURES!!!` `False`.
- **새 held-out 공개 입력**: `outputs/gemma-context-20260912/emulator-tests/heldout-full-01/summary.json`; fixture `held_out_public_20260912`; SHA-256 `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; sample_finished 24개, fixture 집계 일치 `True`, 로그 끝 `OK (1 test)` 1회, `FAILURES!!!` `False`.

## 후보와 수동 품질 결과

- 기존 독립 공개 입력: 후보 **20/23**, strict 품질 **20/20 후보**, 전체 completion 분모 기준 **20/23**. 후보 없음 h15·h22·h23은 평가 불가, h04는 경계 제외.
- 새 held-out 공개 입력: 후보 **23/23**, strict 품질 **19/23 후보**. g03·g08·g09·g23의 root 확정 지적을 아래 표에 그대로 남겼고, g24는 경계 제외.

## 지연 증거

다음 값은 각 실행에서 `candidatePresent=true`인 행만 사용한 firstText/total의 nearest-rank p50·p95, 최소·최대다. 이 실행별 evidence 통계를 실기기 성능 수치로 혼합하거나 일반화하지 않는다.

| 입력군 | 후보 행 | firstText ms (min / p50 / p95 / max) | total ms (min / p50 / p95 / max) |
| --- | ---: | --- | --- |
| 기존 독립 공개 입력 | 20 | 2512 / 3938 / 12648 / 22437 | 3378 / 5430 / 13773 / 23865 |
| 새 held-out 공개 입력 | 23 | 3087 / 3799 / 6840 / 9788 | 3990 / 5119 / 9089 / 12643 |

## 입력별 수동 판정

`근거`의 line은 summary 안의 `sample_finished` source line이다. 후보 원문은 evidence에서 그대로 옮겼으며 수정하지 않았다.

| 입력군·ID | raw 입력 | 후보 전문 | 의미 | 조사 | 어미 | 띄어쓰기 | 종결형 | 이유 | 근거 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| existing h01 | 내일 오전에  | 내일 오전에 중요한 회의가 예정되어 있습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 12 |
| existing h02 | 가능한 시간을  | 가능한 시간을 알려주세요. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 14 |
| existing h03 | 버스가 늦어서  | 버스가 늦어서 약속에 늦을까 걱정되었다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 16 |
| existing h04 | 역에 도착했어요.  | — | 제외 | 제외 | 제외 | 제외 | 제외 | 경계 입력: 문장부호 뒤 생성 미시작. 후보 분모와 품질 평가에서 제외. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 18 |
| existing h05 | 저녁 메뉴는  | 저녁 메뉴는 무엇으로 정할까요? | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 20 |
| existing h06 | 식당 예약을  | 식당 예약을 하려면 미리 전화로 문의해야 합니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 22 |
| existing h07 | 빨래를 널고  | 빨래를 널고 마실 것을 준비했다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 24 |
| existing h08 | 설거지는 제가  | 설거지는 제가 책임지겠습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 26 |
| existing h09 | 주문한 물건이  | 주문한 물건이 도착하기를 기다리고 있다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 28 |
| existing h10 | 사이즈가 맞지  | 사이즈가 맞지 않아서 다시 주문해야겠어요. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 30 |
| existing h11 | 검토가 끝나면  | 검토가 끝나면 다음 단계를 진행하겠습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 32 |
| existing h12 | 수정한 내용을  | 수정한 내용을 바탕으로 최종 검토를 진행하겠습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 34 |
| existing h13 | 이 부분이  | 이 부분이 핵심적인 내용을 담고 있습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 36 |
| existing h14 | 문제 풀이를  | 문제 풀이를 통해 학습 내용을 확실히 이해할 수 있습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 38 |
| existing h15 | 주말에 산책  | — | 평가 불가 | 평가 불가 | 평가 불가 | 평가 불가 | 평가 불가 | 후보 없음: 각 차원 평가 불가. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 40 |
| existing h16 | 영화를 보고  | 영화를 보고 나는 깊은 감동을 받았다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 42 |
| existing h17 | 감기 기운이  | 감기 기운이 도는 것을 느꼈다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 44 |
| existing h18 | 오늘은 일찍  | 오늘은 일찍 일어나서 산책을 하려고 한다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 46 |
| existing h19 | 방문 전에  | 방문 전에 미리 준비해야 할 사항들을 꼼꼼히 확인해야 합니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 48 |
| existing h20 | 모임 장소가  | 모임 장소가 어디로 정해졌는지 알려주세요. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 50 |
| existing h21 | 도와주신 덕분에  | 도와주신 덕분에 큰 어려움을 극복할 수 있었습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 52 |
| existing h22 | 걱정해 줘서  | — | 평가 불가 | 평가 불가 | 평가 불가 | 평가 불가 | 평가 불가 | 후보 없음: 각 차원 평가 불가. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 54 |
| existing h23 | 요즘 어떻게  | — | 평가 불가 | 평가 불가 | 평가 불가 | 평가 불가 | 평가 불가 | 후보 없음: 각 차원 평가 불가. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 56 |
| existing h24 | 잘 지내고  | 잘 지내고 있기를 바랍니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `25f39614d3f72a59a2c0931658c1e10a2f2a46642dc534a1eeaf7dee3dae41cc`; line 58 |
| heldout g01 | 금요일 오후 일정이 갑자기  | 금요일 오후 일정이 갑자기 변경되어 참석이 어려울 것 같습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 12 |
| heldout g02 | 다음 달 첫째 주에 회의를  | 다음 달 첫째 주에 회의를 통해 중요한 안건들을 논의할 예정입니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 14 |
| heldout g03 | 전철을 갈아타려 | 전철을 갈아타려다가 환승역을 찾았다. | 주의 | 통과 | 주의 | 통과 | 통과 | 갈아타려다가 환승역을 찾았다: 목적 관계에 중단/대조 어미가 어색함. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 16 |
| heldout g04 | 비행기가 연착되 | 비행기가 연착되어서 승객들이 불편을 겪고 있다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 18 |
| heldout g05 | 비 오는 날에는 국물을  | 비 오는 날에는 국물을 끓여 먹는 것이 참 좋습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 20 |
| heldout g06 | 알레르기가 있어서 재료를  | 알레르기가 있어서 재료를 따로 준비해야 합니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 22 |
| heldout g07 | 욕실 청소를 마치고 나서  | 욕실 청소를 마치고 나서 기분까지 상쾌해졌다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 24 |
| heldout g08 | 세탁기 소리가 나서  | 세탁기 소리가 나서 고장난 것 같다. | 주의 | 통과 | 통과 | 실패 | 통과 | 소리만으로 고장을 추론하며 이상 소리 언급이 없음. ‘고장난’은 ‘고장 난’으로 띄어 써야 함(국립국어원 https://www.korean.go.kr/front/onlineQna/onlineQnaView.do?mn_id=261&pageIndex=1&qna_seq=318929). | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 26 |
| heldout g09 | 장바구니에 담은 책을  | 장바구니에 담은 책을 이제 읽을 시간이다. | 주의 | 통과 | 통과 | 통과 | 통과 | 장바구니 책을 곧바로 읽기: 구매/수령 과정이 생략됨. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 28 |
| heldout g10 | 교환 신청서를 작성했 | 교환 신청서를 작성했으니 이제 제출하시면 됩니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 30 |
| heldout g11 | 회의 자료를 공유하 | 회의 자료를 공유하셔서 모두가 내용을 숙지할 수 있도록 준비해 주시기 바랍니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 32 |
| heldout g12 | 팀원 의견을 반영했 | 팀원 의견을 반영했으니 이제 프로젝트를 성공적으로 마무리할 수 있을 것입니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 34 |
| heldout g13 | 새 단어를 외울 때는  | 새 단어를 외울 때는 꾸준히 반복해서 듣고 써보는 것이 도움이 됩니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 36 |
| heldout g14 | 과제 제출 기한이  | 과제 제출 기한이 곧 다가오고 있으니 서둘러 마무리해야 합니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 38 |
| heldout g15 | 주말 저녁에 기타를  | 주말 저녁에 기타를 치며 편안한 시간을 보냈다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 40 |
| heldout g16 | 사진을 정리하 | 사진을 정리하면 훨씬 보기 좋아집니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 42 |
| heldout g17 | 눈이 많이 피곤해서  | 눈이 많이 피곤해서 오늘은 일찍 자려고 합니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 44 |
| heldout g18 | 약을 먹은 뒤에는  | 약을 먹은 뒤에는 몸의 변화를 주의 깊게 살펴보아야 합니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 46 |
| heldout g19 | 도서관에 들렀 | 도서관에 들렀더니 흥미로운 책들을 발견했다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 48 |
| heldout g20 | 손님이 오시기 전에  | 손님이 오시기 전에 준비를 철저히 해야 합니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 50 |
| heldout g21 | 세심하게 알려 주셔서  | 세심하게 알려 주셔서 정말 감사드립니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 52 |
| heldout g22 | 시간 내 주신 덕분에  | 시간 내 주신 덕분에 일이 순조롭게 진행될 수 있었습니다. | 통과 | 통과 | 통과 | 통과 | 통과 | root 확정 수동 판정: 통과. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 54 |
| heldout g23 | 최근에 지내시는 건  | 최근에 지내시는 건 잘 지내고 계신지 궁금합니다. | 실패 | 통과 | 실패 | 통과 | 통과 | ‘최근에 지내시는 건 잘 지내고 계신지 궁금합니다.’: 주제 구문과 의문 내포절이 중복되어 문장 구조가 부자연스러움. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 56 |
| heldout g24 | 오늘 일정은 여기까지 마쳤어요.  | — | 제외 | 제외 | 제외 | 제외 | 제외 | 경계 입력: 문장부호 뒤 생성 미시작. 후보 분모와 품질 평가에서 제외. | SHA `043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5`; line 58 |
