import { useEffect, useState } from 'react';
import { getCategoriesWithSubServices } from '../../shared/api';
import type { CategoryWithSubServicesResponse } from '../../shared/api';
import { ProfessionIllustration } from '../../shared/components';
import styles from './SupportedCategoriesList.module.css';

/**
 * "כרגע אנחנו תומכים ב:" — the trades Pronto actually offers, read from the platform's own
 * source of truth.
 *
 * ## Where the list comes from, and why it is not a constant in this file
 *
 * `GET /api/categories`, which projects the seeded `categories` table. That table is the same one
 * `ai.catalog.ServiceCategoryCatalog` reads to build the classifier's category enum — so the list
 * a customer is shown here and the set a classification can possibly resolve to are **the same
 * rows by construction**, not two lists someone has to remember to keep in step. A category added
 * or removed by a migration changes both at once, with no frontend release.
 *
 * That also rules out the tempting shortcut: `shared/api/categories.ts` exports a static
 * `CATEGORIES` array with the right Hebrew names in it. It is a hand-maintained mirror (its own
 * doc comment says so, and says to replace it with a fetch once an endpoint exists — the endpoint
 * exists). Rendering "what we support" from a copy is exactly the drift this screen must not have:
 * the one screen whose entire job is to be accurate about our coverage.
 *
 * ## Informational only
 *
 * These are not availability, not a search result, and not clickable. See the parent's Javadoc for
 * why nothing here navigates.
 *
 * ## States
 *
 * Loading renders nothing at all, deliberately. The parent's message ("we don't cover this") is
 * complete and true on its own; a skeleton would push it around the screen and imply something
 * important is still coming. Failure and an empty catalogue are treated the same way and for the
 * same reason — this section is an enhancement, and a customer who has just been told Pronto
 * cannot help them is not helped by an error about a second request they never made.
 */
export function SupportedCategoriesList() {
  const [categories, setCategories] = useState<CategoryWithSubServicesResponse[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    getCategoriesWithSubServices()
      .then((result) => {
        if (!cancelled) {
          setCategories(result);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setCategories([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (categories === null || categories.length === 0) {
    return null;
  }

  return (
    <section className={styles.section} aria-labelledby="supported-categories-heading">
      <h2 id="supported-categories-heading" className={styles.heading}>
        כרגע אנחנו תומכים ב:
      </h2>
      <ul className={styles.grid}>
        {categories.map((category) => (
          <li key={category.id} className={styles.item}>
            {/* Keyed on the backend's own category id, so a category with no drawing yet falls
                back to the Mascot rather than borrowing another trade's illustration. */}
            <ProfessionIllustration categoryId={category.id} className={styles.illustration} />
            <span className={styles.name}>{category.nameHe}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
