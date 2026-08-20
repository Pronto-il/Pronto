import { useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Checkbox,
  EmptyState,
  FilterChipGroup,
  Input,
  Mascot,
  Modal,
  PageHeader,
  Select,
  Skeleton,
  Textarea,
} from '../shared/components';
import type { BadgeSize, BadgeTone } from '../shared/components';
import type { ButtonVariant } from '../shared/components';
import type { MascotSize, MascotState } from '../shared/components';
import { useToast } from '../shared/hooks';
import styles from './DesignSystemPage.module.css';

/**
 * Dev-only visual QA/showcase route for MS1 ("Visual Foundation & Motion System"), per the
 * approved plan (`docs/architecture` — MS1 plan Architecture §9, "Verification surface").
 * Renders every token/primitive MS1 introduced or upgraded, using only real components and
 * their existing public APIs (no new props/behavior added to make a section demonstrable —
 * a section that can't be shown with the existing API is a signal to report a gap, not to
 * quietly extend a component here).
 *
 * Route wiring: `app/router.tsx` adds `/__design` as a top-level sibling of the `AppLayout`
 * tree (not nested under it, so it needs no app chrome/auth), gated behind
 * `import.meta.env.DEV` so this whole route is absent from production builds. This file is
 * rendered inside `App.tsx`'s existing `ToastProvider`/`RouterProvider` tree like every other
 * route, so `useToast()` works with no special wiring (see the Toast section below).
 *
 * Not a product page — internal tool only, kept out of `docs/architecture` route tables.
 */
export default function DesignSystemPage() {
  return (
    <div className={`page-container ${styles.page}`}>
      <h1 className={styles.pageTitle}>Pronto Design System — MS1</h1>
      <p className={styles.pageSubtitle}>
        עמוד בדיקה פנימי (dev-only, `/__design`) להצגת כל הטוקנים והרכיבים המשותפים שנוספו/עודכנו
        ב-MS1. לא חלק מהמוצר.
      </p>

      <TypeScaleSection />
      <ShadowsSection />
      <ButtonSection />
      <CardSection />
      <InputFamilySection />
      <BadgeSection />
      <FilterChipGroupSection />
      <SkeletonSection />
      <EmptyStateSection />
      <PageHeaderSection />
      <ModalSection />
      <ToastSection />
      <MascotSection />
    </div>
  );
}

const TYPE_TOKENS = [
  '--font-size-display',
  '--font-size-h1',
  '--font-size-h1-mobile',
  '--font-size-h2',
  '--font-size-h3',
  '--font-size-body-lg',
  '--font-size-body',
  '--font-size-small',
  '--font-size-caption',
] as const;

function TypeScaleSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>1. Type scale</h2>
      <p className={styles.sectionDescription}>
        --font-size-display / h1 / h1-mobile / h2 / h3 / body-lg / body / small / caption
      </p>
      <div className={styles.stack}>
        {TYPE_TOKENS.map((token) => (
          <div key={token} className={styles.tokenSample}>
            <span className={styles.tokenLabel}>{token}</span>
            <span className={styles.tokenValue} style={{ fontSize: `var(${token})` }}>
              פרונטו מוצא לך את בעל המקצוע המתאים תוך דקות
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}

function ShadowsSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>2. Shadows</h2>
      <p className={styles.sectionDescription}>--shadow-elevated / --shadow-modal</p>
      <div className={styles.row}>
        <div className={styles.shadowSwatch} style={{ boxShadow: 'var(--shadow-elevated)' }}>
          --shadow-elevated
        </div>
        <div className={styles.shadowSwatch} style={{ boxShadow: 'var(--shadow-modal)' }}>
          --shadow-modal
        </div>
      </div>
    </section>
  );
}

const BUTTON_VARIANTS: ButtonVariant[] = ['primary', 'secondary', 'ghost', 'destructive'];

function ButtonSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>3. Button</h2>
      <p className={styles.sectionDescription}>
        כל הוריאנטים (variant) × normal / disabled / loading. לחיצה (:active) מציגה
        press-scale (--motion-press-scale) שלא ניתן לצלם באופן סטטי.
      </p>
      <div className={styles.grid}>
        {BUTTON_VARIANTS.map((variant) => (
          <div key={variant} className={styles.componentBlock}>
            <span className={styles.componentBlockLabel}>variant=&quot;{variant}&quot;</span>
            <Button variant={variant}>כפתור רגיל</Button>
            <Button variant={variant} disabled>
              כפתור מנוטרל
            </Button>
            <Button variant={variant} loading>
              טוען
            </Button>
          </div>
        ))}
      </div>
    </section>
  );
}

function CardSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>4. Card</h2>
      <p className={styles.sectionDescription}>
        interactive=&#123;false&#125; (ברירת מחדל, ללא משוב) לעומת interactive=&#123;true&#125;
        (hover/press על שולחן עבודה בלבד)
      </p>
      <div className={styles.row}>
        <div className={styles.componentBlock}>
          <span className={styles.componentBlockLabel}>interactive=&#123;false&#125;</span>
          <Card className={styles.cardDemo}>
            <p>כרטיס רגיל, ללא משוב אינטראקטיבי.</p>
          </Card>
        </div>
        <div className={styles.componentBlock}>
          <span className={styles.componentBlockLabel}>interactive=&#123;true&#125;</span>
          <Card interactive className={styles.cardDemo}>
            <p>כרטיס אינטראקטיבי — יש לרחף עם העכבר כדי לבדוק hover/press.</p>
          </Card>
        </div>
      </div>
    </section>
  );
}

const SELECT_OPTIONS = [
  { value: 'plumbing', label: 'אינסטלציה' },
  { value: 'electrical', label: 'חשמל' },
  { value: 'ac', label: 'מיזוג אוויר' },
];

function InputFamilySection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>5. Input family</h2>
      <p className={styles.sectionDescription}>
        Input / Textarea / Select / Checkbox — normal / focused (עמעום אוטומטי בטעינה) / error.
        הערה: Checkbox אינו תומך במצב error כרגע (אין prop כזה בממשק שלו) — הודגם ללא מצב זה.
      </p>
      <div className={styles.formGrid}>
        <Input label="שם מלא" placeholder="ישראל ישראלי" />
        <Input label="טלפון (פוקוס)" placeholder="050-1234567" autoFocus hint="שדה זה נטען עם פוקוס אוטומטי כדי להדגים את מצב הפוקוס" />
        <Input label="כתובת אימייל" error="כתובת אימייל לא תקינה" defaultValue="not-an-email" />

        <Textarea label="תיאור התקלה" placeholder="ספר לנו מה קרה..." />
        <Textarea label="הערות (פוקוס)" hint="שדה זה מדגים את מצב הפוקוס" />
        <Textarea label="תיאור התקלה" error="שדה חובה" />

        <Select label="קטגוריית שירות" options={SELECT_OPTIONS} placeholder="בחר קטגוריה" />
        <Select label="קטגוריית שירות (פוקוס)" options={SELECT_OPTIONS} hint="שדה זה מדגים את מצב הפוקוס" />
        <Select label="קטגוריית שירות" options={SELECT_OPTIONS} error="יש לבחור קטגוריה" />

        <Checkbox label="אני מאשר את תנאי השימוש" />
        <Checkbox label="מסומן" defaultChecked />
      </div>
    </section>
  );
}

const BADGE_TONES: BadgeTone[] = ['neutral', 'primary', 'success', 'warning', 'error', 'info'];
const BADGE_SIZES: BadgeSize[] = ['sm', 'md'];

function BadgeSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>6. Badge</h2>
      <p className={styles.sectionDescription}>6 גוונים (tone) × 2 גדלים (size)</p>
      <div className={styles.stack}>
        {BADGE_SIZES.map((size) => (
          <div key={size} className={styles.row}>
            {BADGE_TONES.map((tone) => (
              <Badge key={tone} tone={tone} size={size}>
                {tone} / {size}
              </Badge>
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}

const SORT_OPTIONS = [
  { value: 'recommended', label: 'מומלץ' },
  { value: 'rating', label: 'דירוג גבוה' },
  { value: 'price', label: 'מחיר: מהנמוך לגבוה' },
];

function FilterChipGroupSection() {
  const [sort, setSort] = useState<(typeof SORT_OPTIONS)[number]['value']>('recommended');

  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>7. FilterChipGroup</h2>
      <p className={styles.sectionDescription}>
        role=&quot;radiogroup&quot; אינטראקטיבי (Tab + Enter/Space) — נבחר כרגע: {sort}
      </p>
      <FilterChipGroup aria-label="מיון תוצאות" options={SORT_OPTIONS} value={sort} onChange={setSort} />
    </section>
  );
}

function SkeletonSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>8. Skeleton</h2>
      <p className={styles.sectionDescription}>variant=&quot;text&quot; (lines=3) / &quot;rect&quot; / &quot;circle&quot;</p>
      <div className={styles.row}>
        <div className={styles.componentBlock}>
          <span className={styles.componentBlockLabel}>text, lines=3</span>
          <div style={{ width: 220 }}>
            <Skeleton variant="text" lines={3} />
          </div>
        </div>
        <div className={styles.componentBlock}>
          <span className={styles.componentBlockLabel}>rect</span>
          <Skeleton variant="rect" className={styles.skeletonRect} />
        </div>
        <div className={styles.componentBlock}>
          <span className={styles.componentBlockLabel}>circle</span>
          <Skeleton variant="circle" className={styles.skeletonCircle} />
        </div>
      </div>
    </section>
  );
}

function EmptyStateSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>9. EmptyState</h2>
      <p className={styles.sectionDescription}>tone=&quot;neutral&quot; / tone=&quot;error&quot;</p>
      <div className={styles.row}>
        <div className={styles.componentBlock} style={{ width: 320 }}>
          <EmptyState
            tone="neutral"
            title="עדיין אין הזמנות"
            description="ברגע שתפתח קריאת שירות חדשה, היא תופיע כאן."
            action={<Button variant="secondary">פתיחת קריאה חדשה</Button>}
          />
        </div>
        <div className={styles.componentBlock} style={{ width: 320 }}>
          <EmptyState
            tone="error"
            title="שגיאה בטעינת הנתונים"
            description="לא הצלחנו לטעון את הנתונים כרגע. נסה שוב."
            action={<Button variant="secondary">ניסיון חוזר</Button>}
          />
        </div>
      </div>
    </section>
  );
}

function PageHeaderSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>10. PageHeader</h2>
      <p className={styles.sectionDescription}>
        עם steps=&#123;&#123; current: 2, total: 4 &#125;&#125; ו-onBack (בדיקת אזור לחיצה 44px)
      </p>
      <Card>
        <PageHeader
          title="פרטי התקלה"
          description="שלב 2 מתוך 4"
          onBack={() => {
            /* dev-only showcase — no navigation */
          }}
          steps={{ current: 2, total: 4 }}
        />
      </Card>
    </section>
  );
}

function ModalSection() {
  const [isSheetModalOpen, setIsSheetModalOpen] = useState(false);
  const [isDialogModalOpen, setIsDialogModalOpen] = useState(false);

  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>11. Modal</h2>
      <p className={styles.sectionDescription}>
        mobilePresentation=&quot;sheet&quot; (ברירת מחדל) לעומת &quot;dialog&quot;
      </p>
      <div className={styles.row}>
        <Button onClick={() => setIsSheetModalOpen(true)}>פתיחת מודל (sheet, ברירת מחדל)</Button>
        <Button variant="secondary" onClick={() => setIsDialogModalOpen(true)}>
          פתיחת מודל (dialog, נשאר מרכזי במובייל)
        </Button>
      </div>

      <Modal
        isOpen={isSheetModalOpen}
        onClose={() => setIsSheetModalOpen(false)}
        title="אישור פעולה"
        footer={
          <>
            <Button variant="ghost" onClick={() => setIsSheetModalOpen(false)}>
              ביטול
            </Button>
            <Button onClick={() => setIsSheetModalOpen(false)}>אישור</Button>
          </>
        }
      >
        <p>זהו מודל לדוגמה עם mobilePresentation=&quot;sheet&quot; (ברירת המחדל).</p>
      </Modal>

      <Modal
        isOpen={isDialogModalOpen}
        onClose={() => setIsDialogModalOpen(false)}
        title="אישור פעולה (דיאלוג)"
        mobilePresentation="dialog"
        footer={
          <>
            <Button variant="ghost" onClick={() => setIsDialogModalOpen(false)}>
              ביטול
            </Button>
            <Button onClick={() => setIsDialogModalOpen(false)}>אישור</Button>
          </>
        }
      >
        <p>זהו מודל לדוגמה עם mobilePresentation=&quot;dialog&quot; — נשאר דיאלוג מרכזי גם במובייל.</p>
      </Modal>
    </section>
  );
}

function ToastSection() {
  const { showToast } = useToast();

  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>12. Toast</h2>
      <p className={styles.sectionDescription}>useToast().showToast(message, &#123; tone &#125;)</p>
      <div className={styles.row}>
        <Button variant="secondary" onClick={() => showToast('הפעולה בוצעה בהצלחה', { tone: 'success' })}>
          הצגת טוסט הצלחה
        </Button>
        <Button variant="secondary" onClick={() => showToast('אירעה שגיאה, נסה שוב', { tone: 'error' })}>
          הצגת טוסט שגיאה
        </Button>
        <Button variant="secondary" onClick={() => showToast('עדכון חדש זמין', { tone: 'info' })}>
          הצגת טוסט מידע
        </Button>
        <Button variant="secondary" onClick={() => showToast('הודעה כללית')}>
          הצגת טוסט ניטרלי
        </Button>
      </div>
    </section>
  );
}

const MASCOT_STATES: MascotState[] = ['idle', 'running', 'thinking', 'searching', 'found', 'success'];
const MASCOT_SIZES: MascotSize[] = ['sm', 'md', 'lg', 'xl'];
const MASCOT_BACKGROUNDS: { key: string; label: string; className: string }[] = [
  { key: 'white', label: 'רקע לבן (#ffffff)', className: styles.mascotBgWhite },
  { key: 'app', label: 'רקע אפליקציה (--color-background)', className: styles.mascotBgApp },
  { key: 'tint', label: 'רקע מודגש (--color-primary-light)', className: styles.mascotBgTint },
];

function MascotSection() {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>13. Mascot</h2>
      <p className={styles.sectionDescription}>
        6 מצבים × 4 גדלים, כל אחד גם עם loop ברירת המחדל וגם עם loop=&#123;false&#125;, על 3 רקעים
        שונים כדי לבדוק נאמנות צבע (ללא הילה שחורה, כחול נכון, מתאר לבן).
      </p>
      <div className={styles.mascotBackgroundGrid}>
        {MASCOT_BACKGROUNDS.map((background) => (
          <div key={background.key} className={`${styles.mascotBackground} ${background.className}`}>
            <span className={styles.mascotBackgroundLabel}>{background.label}</span>
            {MASCOT_STATES.map((state) => (
              <div key={state} className={styles.mascotStateGroup}>
                <span className={styles.mascotStateLabel}>
                  state=&quot;{state}&quot; (loop ברירת מחדל)
                </span>
                <div className={styles.mascotSizeRow}>
                  {MASCOT_SIZES.map((size) => (
                    <div key={size} className={styles.mascotSizeCell}>
                      <Mascot state={state} size={size} label={`מצב ${state}`} />
                      <span className={styles.mascotSizeCellLabel}>{size}</span>
                    </div>
                  ))}
                </div>
                <span className={styles.mascotStateLabel}>state=&quot;{state}&quot; loop=&#123;false&#125;</span>
                <div className={styles.mascotSizeRow}>
                  {MASCOT_SIZES.map((size) => (
                    <div key={`${size}-no-loop`} className={styles.mascotSizeCell}>
                      <Mascot state={state} size={size} loop={false} label={`מצב ${state}, ללא לופ`} />
                      <span className={styles.mascotSizeCellLabel}>{size}</span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}
