package fr.fouss.boardeo;

import android.content.Intent;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TreeMap;

import fr.fouss.boardeo.data.Board;
import fr.fouss.boardeo.data.Comment;
import fr.fouss.boardeo.data.Post;
import fr.fouss.boardeo.listing.CommentAdapter;
import fr.fouss.boardeo.listing.CommentView;
import fr.fouss.boardeo.utils.UserUtils;

public class PostActivity extends AppCompatActivity {

    private UserUtils userUtils;
    private DatabaseReference mDatabase;

    private Board board;
    private Post post;
    private String postKey;

    private boolean boardRetrievalLauched = false;
    private boolean boardRetrieved = false;
    private boolean menuInflated = false;

    private ValueEventListener postListener;

    private Toolbar toolbar;
    private CommentAdapter commentAdapter;

    private String editingCommentKey;
    private Comment editingComment;
    private boolean isEditingComment = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post);

        // data base reference
        userUtils = new UserUtils(this);
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Setup toolbar
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // post infos and fields
        postKey = getIntent().getStringExtra(Post.KEY_FIELD);
        updateTextFields();

        // comment list
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setReverseLayout(true);
        layoutManager.setStackFromEnd(true);

        RecyclerView commentRecyclerView = findViewById(R.id.comment_recycler_view);
        commentRecyclerView.setLayoutManager(layoutManager);
        commentAdapter = new CommentAdapter(this);
        commentRecyclerView.setAdapter(commentAdapter);
        commentAdapter.initCommentListListener(postKey);

        // comment button listener
        findViewById(R.id.comment_button).setOnClickListener(this::onCommentButtonClick);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        switch (item.getItemId()) {
            case R.id.comment_edit_menu_item:
                onEditCommentMenuItemClick(commentAdapter.getKey(info.position),
                        commentAdapter.getComment(info.position));
                return true;
            case R.id.comment_delete_menu_item:
                onDeleteCommentMenuItemClick(commentAdapter.getKey(info.position));
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private void onDeleteCommentMenuItemClick(String key) {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setMessage("Do you really want to delete this comment ?")
                .setTitle("Deletion")
                .setCancelable(false)
                .setPositiveButton("Yes", (dialog, which) -> deleteComment(key))
                .setNegativeButton("No", (dialog, which) -> {});
        alert.create().show();
    }

    private void deleteComment(String key) {
        mDatabase.child("posts")
                .child(postKey)
                .child("comments")
                .child(key)
                .removeValue();

        mDatabase.child("comments")
                .child(key)
                .removeValue();
    }

    private void onEditCommentMenuItemClick(String key, Comment comment) {
        isEditingComment = true;
        editingComment = comment;
        editingCommentKey = key;
        EditText editText = findViewById(R.id.comment_edit_text);
        editText.setText(comment.getContent());
        editText.requestFocus();
        editText.setSelection(editText.length());
    }


    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        // inflate menu
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.comment_context_menu, menu);

        // get corresponding comment key
        CommentView commentView = v.findViewById(R.id.comment_view);
        Comment comment = commentAdapter.getComment(commentView.getPosition());

        if (userUtils.getUserUid().equals(comment.getAuthorUid())) {
        // author
        menu.findItem(R.id.comment_delete_menu_item).setVisible(true);
        menu.findItem(R.id.comment_edit_menu_item).setVisible(true);
        } else if (board.getOwnerUid().equals(userUtils.getUserUid())) {
        menu.findItem(R.id.comment_delete_menu_item).setVisible(true);
        menu.findItem(R.id.comment_edit_menu_item).setVisible(false);
        } else {
        menu.findItem(R.id.comment_delete_menu_item).setVisible(false);
        menu.findItem(R.id.comment_edit_menu_item).setVisible(false);
        }
    }

    private void onCommentButtonClick(View v) {

        EditText commentEditText = findViewById(R.id.comment_edit_text);
        if (commentEditText.length() == 0) {
            commentEditText.setError("Missing");
            return;
        }

        if (isEditingComment) {
            isEditingComment = false;

            TreeMap<String, Object> updates = new TreeMap<>();
            updates.put("content", commentEditText.getText().toString());
            mDatabase.child("comments")
                    .child(editingCommentKey)
                    .updateChildren(updates);

            commentEditText.setText("");

        } else {
            // create comment in comments
            Comment newComment = new Comment(
                    commentEditText.getText().toString(),
                    new Date().getTime(),
                    userUtils.getUserUid(),
                    postKey);
            String newCommentKey = mDatabase
                    .child("comments")
                    .push().getKey();
            mDatabase
                    .child("comments")
                    .child(newCommentKey)
                    .setValue(newComment);

            // set comment ref in post
            mDatabase
                    .child("posts")
                    .child(postKey)
                    .child("comments")
                    .child(newCommentKey)
                    .setValue("true");

            // clear comment edit text
            commentEditText.setText("");
        }
    }

    private void retrieveBoard() {
        DatabaseReference dataReference = mDatabase.child("boards").child(post.getBoardKey());

        dataReference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                board = dataSnapshot.getValue(Board.class);
                assert board != null;

                // Setup of colors
                findViewById(R.id.app_bar_container).setBackgroundColor(board.getColor().intValue());
                CollapsingToolbarLayout collapseToolbar = findViewById(R.id.collapsing_toolbar_layout);
                collapseToolbar.setBackgroundColor(board.getColor().intValue());
                collapseToolbar.setContentScrimColor(board.getColor().intValue());
                toolbar.setBackgroundColor(board.getColor().intValue());

                boardRetrieved = true;
                updateMenuVisibility();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Toast.makeText(PostActivity.this,
                        "Board info couldn't be retrieved",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateTextFields() {
        DatabaseReference dataReference = mDatabase.child("posts").child(postKey);

        postListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                post = dataSnapshot.getValue(Post.class);
                assert post != null;

                // Setup of texts
                CollapsingToolbarLayout collapseToolbar = findViewById(R.id.collapsing_toolbar_layout);
                collapseToolbar.setTitle(post.getTitle());
                TextView titleLabel = findViewById(R.id.post_title_label);
                TextView contentLabel = findViewById(R.id.post_content_label);
                TextView dateLabel = findViewById(R.id.post_date_label);
                TextView authorLabel = findViewById(R.id.post_author_label);

                titleLabel.setText(post.getTitle());
                contentLabel.setText(post.getContent());
                dateLabel.setText(SimpleDateFormat.getDateTimeInstance().format(new Date(post.getTimestamp())));
                mDatabase.child("users").child(post.getAuthorUid()).child("username")
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot dataSnapshot) {
                                authorLabel.setText(getResources().getString(R.string.by_author, dataSnapshot.getValue(String.class)));
                            }

                            @Override
                            public void onCancelled(DatabaseError databaseError) {
                                throw databaseError.toException();
                            }
                        });

                if (!boardRetrievalLauched) {
                    boardRetrievalLauched = true;
                    retrieveBoard();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Toast.makeText(PostActivity.this,
                        "Post info couldn't be retrieved",
                        Toast.LENGTH_SHORT).show();
            }
        };
        dataReference.addValueEventListener(postListener);
    }

    @Override
    public void onBackPressed() {
        onSupportNavigateUp();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.post_menu, menu);
        // Setup of menu
        menuInflated = true;
        updateMenuVisibility();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.post_delete_menu_item :
                onDeleteMenuItemClick();
                return true;
            case R.id.post_edit_menu_item :
                onEditMenuItemClick();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateMenuVisibility() {
        if (menuInflated && boardRetrieved) {
            if (post.getAuthorUid().equals(userUtils.getUserUid())) {
                // author
                toolbar.getMenu().findItem(R.id.post_edit_menu_item).setVisible(true);
                toolbar.getMenu().findItem(R.id.post_delete_menu_item).setVisible(true);
            } else if (board.getOwnerUid().equals(userUtils.getUserUid())) {
                // board owner
                toolbar.getMenu().findItem(R.id.post_delete_menu_item).setVisible(true);
            }
        }
    }

    private void onDeleteMenuItemClick() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setMessage("Do you really want to delete this post ?")
                .setTitle("Deletion")
                .setCancelable(false)
                .setPositiveButton("Yes", (dialog, which) -> deletePost())
                .setNegativeButton("No", (dialog, which) -> {});
        alert.create().show();
    }

    private void deletePost() {
        // remove listener
        mDatabase
                .child("posts")
                .child(postKey)
                .removeEventListener(postListener);

        // unlink in board
        mDatabase
                .child("boards")
                .child(post.getBoardKey())
                .child("posts")
                .child(postKey)
                .removeValue();

        // delete post data in board
        mDatabase.child("posts").child(postKey).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // remove comments data
                for (DataSnapshot commentKey : dataSnapshot.child("comments").getChildren()) {
                    mDatabase.child("comments")
                            .child(commentKey.getKey())
                            .removeValue();
                }
                // remove post
                mDatabase.child("posts")
                        .child(postKey)
                        .removeValue();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });

        finish();
    }

    private void onEditMenuItemClick() {
        Intent intent = new Intent(this, NewPostActivity.class);
        intent.putExtra(Post.KEY_FIELD, postKey);
        intent.putExtra(Board.KEY_FIELD, post.getBoardKey());
        startActivity(intent);
    }
}
