import { Link } from 'react-router-dom';
import styles from './HomePage.module.css';

export default function HomePage() {
    return (
        <div className="page-container">
            <section className={styles.hero}>
                <div className={styles.heroContent}>
                    <h1 className={styles.title}>איך אפשר לעזור היום?</h1>

                    <p className={styles.description}>
                        ספר לנו מה קרה, ו-Pronto יעזור לך למצוא את בעל המקצוע המתאים.
                    </p>

                    <div className={styles.flowArea}>
                        <div className={styles.mascotArea} aria-hidden="true">
                            <div className={styles.motionLines}>
                                <span />
                                <span />
                                <span />
                            </div>

                            <img
                                src="/assets/pronto-runner-wrench.png"
                                alt=""
                                className={styles.mascot}
                            />
                        </div>

                        <Link to="/issues/new" className={styles.cta}>
                            <span className={styles.ctaTitle}>יש לי תקלה</span>

                            <span className={styles.ctaSubtitle}>
                בוא נמצא את האדם המתאים
              </span>

                            <span className={styles.ctaArrow}>←</span>
                        </Link>
                    </div>
                </div>
            </section>
        </div>
    );
}