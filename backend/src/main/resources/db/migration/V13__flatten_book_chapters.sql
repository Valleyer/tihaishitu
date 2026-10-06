CREATE TABLE chapter_flatten_plan AS
WITH RECURSIVE chapter_tree(id, bank_id, path) AS (
    SELECT id, bank_id,
           CAST(CONCAT(LPAD(CAST(sort_order AS CHAR), 10, '0'), ':', id) AS VARCHAR(4000))
      FROM question_bank_chapter
     WHERE parent_id IS NULL
    UNION ALL
    SELECT child.id, child.bank_id,
           CAST(CONCAT(parent.path, '/', LPAD(CAST(child.sort_order AS CHAR), 10, '0'), ':', child.id) AS VARCHAR(4000))
      FROM question_bank_chapter child
      JOIN chapter_tree parent ON parent.id=child.parent_id
)
SELECT id, ROW_NUMBER() OVER (PARTITION BY bank_id ORDER BY path) new_sort_order
  FROM chapter_tree;

UPDATE question_bank_chapter chapter
   SET sort_order=(SELECT plan.new_sort_order FROM chapter_flatten_plan plan WHERE plan.id=chapter.id),
       parent_id=NULL
 WHERE EXISTS (SELECT 1 FROM chapter_flatten_plan plan WHERE plan.id=chapter.id);

DELETE FROM question_bank_chapter
 WHERE NOT EXISTS (SELECT 1 FROM question_bank_knowledge membership
                    WHERE membership.chapter_id=question_bank_chapter.id);

DROP TABLE chapter_flatten_plan;
