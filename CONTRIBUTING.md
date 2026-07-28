# Contributing to Pager

This is primarily a solo-authored portfolio project, but the workflow below is
what I follow for every change — the same discipline a real engineering team
uses. If you're reading this to evaluate my work, this file is a signal too.

## Workflow

1. **Every change starts as an issue.** No exceptions.
   - Bugs use the bug template
   - Features use the feature template
   - Chores (deps, refactors, docs) use the feature template with the `type/chore` label
2. **One issue → one branch → one PR.** Branch names are prefixed:
   - `feat/<slug>` for new features
   - `fix/<slug>` for bug fixes
   - `chore/<slug>` for chores
   - `docs/<slug>` for docs-only changes
3. **PR is squash-merged** into `main`. One clean commit per PR.
4. **`main` is always green.** CI must pass before merge. No exceptions.

## Commit messages

Conventional Commits, short and useful:

```
feat(webhook): add HMAC-SHA256 signature verification (#12)
fix(queue): retry BullMQ jobs with exponential backoff (#18)
chore(deps): bump spring-boot-starter-parent to 3.4.2 (#21)
docs(readme): correct docker-compose port mapping (#23)
```

## Code style

- Java 21 language features are fair game (records, pattern matching, virtual threads)
- Lombok is allowed but keep it to `@Value`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`
- Package layout: `dev.sumituppal.pager.<area>` — see `README.md` for the map
- No `System.out.println`. Use SLF4J.
- Tests live next to the code they test, mirrored under `src/test/java`

## Definition of done for every PR

- [ ] Code compiles and tests pass (`./mvnw test`)
- [ ] CI is green
- [ ] New behavior has at least one test
- [ ] Public API changes are documented in the PR body
- [ ] If the PR touches config, `.env.example` is updated
- [ ] If the PR touches the DB, a Flyway migration exists (no `ddl-auto: update`)
- [ ] The PR body explains any non-obvious decision

## Getting started

See `README.md` § Quick start.

## Questions

Open an issue with the `question` label.
