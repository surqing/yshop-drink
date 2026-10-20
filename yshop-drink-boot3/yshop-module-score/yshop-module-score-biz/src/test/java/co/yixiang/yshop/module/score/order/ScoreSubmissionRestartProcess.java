package co.yixiang.yshop.module.score.order;

/** Runs in a separate JVM to prove replay depends on committed SQL, not heap state. */
public final class ScoreSubmissionRestartProcess {
    public static void main(String[] args) throws Exception {
        try(var f=new ScoreOrderDatabaseFixture()) {
            f.setup("seed".equals(args[0]));
            var p=f.request("1");p.setIdempotencyKey(args[1]);f.orders.submit(1L,p);
            if(f.n("SELECT COUNT(*) FROM yshop_score_submission")!=1 || f.n("SELECT COUNT(*) FROM yshop_score_order")!=1 || f.n("SELECT COUNT(*) FROM yshop_user_bill")!=1 || f.n("SELECT stock FROM yshop_score_product")!=9 || f.n("SELECT sales FROM yshop_score_product")!=1)throw new AssertionError("RESTART_DUPLICATED_MOVEMENT");
            f.decimal("900","SELECT integral FROM yshop_user WHERE id=1");
            System.out.println("SCORE_RESTART_VERIFIED");
        }
    }
}
