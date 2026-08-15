import { Link } from 'react-router-dom';
import { User, Wrench } from 'lucide-react';
import { Card } from '../../shared/components';
import styles from './RoleChooser.module.css';

/**
 * "אני לקוח / אני בעל מקצוע" chooser — two distinct destinations
 * (`/register/customer`, `/register/professional`), not a toggle on a shared form.
 */
export function RoleChooser() {
  return (
    <div className={styles.grid}>
      <Link to="/register/customer" className={styles.cardLink}>
        <Card className={styles.card}>
          <User size={32} className={styles.icon} aria-hidden="true" />
          <h2 className={styles.title}>אני לקוח</h2>
          <p className={styles.description}>מחפש בעל מקצוע לתיקון בבית</p>
        </Card>
      </Link>
      <Link to="/register/professional" className={styles.cardLink}>
        <Card className={styles.card}>
          <Wrench size={32} className={styles.icon} aria-hidden="true" />
          <h2 className={styles.title}>אני בעל מקצוע</h2>
          <p className={styles.description}>רוצה לקבל פניות מלקוחות דרך Pronto</p>
        </Card>
      </Link>
    </div>
  );
}
