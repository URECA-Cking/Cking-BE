package kr.co.cking.interest.presentation.dto;

import java.util.List;
import kr.co.cking.interest.application.dto.SelectableInterests;

public final class InterestResponse {

    private InterestResponse() {
    }

    public record Selectable(
            String taxonomyVersion,
            int maxSelection,
            String subtopicVersion,
            int maxSubtopicPerParent,
            List<Item> items
    ) {

        public static Selectable from(SelectableInterests view) {
            return new Selectable(
                    view.taxonomyVersion(),
                    view.maxSelection(),
                    view.subtopicVersion(),
                    view.maxSubtopicPerParent(),
                    view.items().stream().map(Item::from).toList());
        }
    }

    public record Item(String interestCode, String name, int displayOrder, List<Subtopic> subtopics) {

        private static Item from(SelectableInterests.Item item) {
            return new Item(item.interestCode(), item.name(), item.displayOrder(),
                    item.subtopics().stream().map(Subtopic::from).toList());
        }
    }

    public record Subtopic(String subtopicCode, String name, int displayOrder) {

        private static Subtopic from(SelectableInterests.Subtopic subtopic) {
            return new Subtopic(subtopic.subtopicCode(), subtopic.name(), subtopic.displayOrder());
        }
    }
}
