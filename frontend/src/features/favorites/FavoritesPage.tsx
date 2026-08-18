import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader, Button } from '../../shared/components';
import { getFavorites, removeFavorite, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { FavoriteProfessionalSummary } from '../../shared/api';
import { FavoriteProfessionalCard } from './FavoriteProfessionalCard';
import styles from './FavoritesPage.module.css';

/**
 * `/favorites` — CUSTOMER-only (matches the backend's CUSTOMER-only `GET /api/favorites`),
 * per `frontend-ms8-design.md` §3. `created_at DESC`, no pagination (backend-confirmed,
 * §6 Risk 7) — rendered as a simple client-side list, same MVP-scale tolerance every other
 * unpaginated list endpoint already gets in this codebase.
 *
 * "Remove" is optimistic (list item disappears immediately) with a revert-on-failure — the
 * same reasoning `ProfessionalProfilePage`'s favorite toggle uses, since this is a
 * deliberate, single-shot user action on its own dedicated screen (not a background poll
 * that self-corrects on the next tick, unlike `NotificationBell`'s optimistic mark-read).
 */
export default function FavoritesPage() {
  const navigate = useNavigate();
  const [favorites, setFavorites] = useState<FavoriteProfessionalSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    getFavorites()
      .then((result) => {
        if (!cancelled) {
          setFavorites(result.favorites);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError(GENERIC_ERROR_MESSAGE);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleRemove(professionalId: number) {
    const previous = favorites;
    setFavorites((prev) => (prev ? prev.filter((item) => item.professionalId !== professionalId) : prev));
    setRemovingId(professionalId);
    setError(null);
    try {
      await removeFavorite(professionalId);
    } catch {
      setFavorites(previous ?? null);
      setError(GENERIC_ERROR_MESSAGE);
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div className="focused-page">
      <PageHeader title="מועדפים" />

      {error && (
        <div className={styles.banner} role="alert">
          <p>{error}</p>
        </div>
      )}

      {!error && favorites === null && <p>טוען…</p>}

      {favorites !== null && favorites.length === 0 && (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>אין עדיין מועדפים</p>
          <p className={styles.emptyText}>אפשר לסמן בעלי מקצוע כמועדפים מתוך דף הפרופיל שלהם.</p>
          <Button onClick={() => navigate('/')}>חזרה לדף הבית</Button>
        </div>
      )}

      {favorites !== null && favorites.length > 0 && (
        <div className={styles.list}>
          {favorites.map((favorite) => (
            <FavoriteProfessionalCard
              key={favorite.professionalId}
              favorite={favorite}
              onRemove={handleRemove}
              isRemoving={removingId === favorite.professionalId}
            />
          ))}
        </div>
      )}
    </div>
  );
}
