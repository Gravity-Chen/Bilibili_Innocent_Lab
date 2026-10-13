package kntr.common.comment.card.renderer.comment;

import androidx.compose.runtime.Composer;
import kntr.common.comment.card.model.comment.ActionBarButtonType;
import kntr.common.comment.card.model.comment.ReplyButtonTestModel;

/** ABI variants captured from 8.96.0, 9.3.0, and 9.15.0. */
public final class CommentActionBarRenderer {
    private void I(ActionBarButtonType type, ReplyButtonTestModel model,
                   Object state, Object dispatcher, Composer composer, int changed) {}

    public void f(ActionBarButtonType type, ReplyButtonTestModel model, boolean expanded,
                  long color, boolean selected, Object dispatcher, Object modifier,
                  Composer composer, int changed, int defaults) {}

    public void f(ActionBarButtonType type, ReplyButtonTestModel model, boolean expanded,
                  long color, boolean selected, Object dispatcher, Object modifier,
                  int height, Composer composer, int changed, int defaults) {}

    public void renderBar(ReplyButtonTestModel model, Object dispatcher, Composer composer, int changed) {}
    public void replyAction(ActionBarButtonType type, ReplyButtonTestModel model) {}
}
