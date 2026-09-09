package interview.pilot.interview.domain;

import java.util.HashSet;
import java.util.List;

/** Frozen order and follow-up budgets, independent of personal-practice presets. */
public record InterviewExecutionPlan(List<Card> cards) {
  public InterviewExecutionPlan {
    cards = List.copyOf(cards);
    if (cards.isEmpty() || cards.size() > 20) throw new IllegalArgumentException("invalid execution plan size");
    var ids = new HashSet<Long>();
    for (Card card : cards) if (!ids.add(card.id())) throw new IllegalArgumentException("duplicate card in execution plan");
  }

  public Card first() { return cards.getFirst(); }

  public Step next(long currentCardId, int followUpsAsked) {
    if (followUpsAsked < 0) throw new IllegalArgumentException("negative follow-up count");
    for (int index = 0; index < cards.size(); index++) {
      Card current = cards.get(index);
      if (current.id() != currentCardId) continue;
      if (followUpsAsked > current.followUpQuota()) throw new IllegalArgumentException("follow-up budget exceeded");
      if (followUpsAsked < current.followUpQuota()) return new Step(current, true);
      return new Step(index + 1 < cards.size() ? cards.get(index + 1) : null, false);
    }
    throw new IllegalArgumentException("current card is not in the frozen execution plan");
  }

  public record Card(long id, InterviewPhase phase, int followUpQuota) {
    public Card {
      if (id < 1 || phase == null || followUpQuota < 0 || followUpQuota > 2
          || (!phase.allowsFollowUp() && followUpQuota != 0)) throw new IllegalArgumentException("invalid execution card");
    }
  }
  public record Step(Card card, boolean followUp) {
    public boolean finished() { return card == null; }
  }
}
